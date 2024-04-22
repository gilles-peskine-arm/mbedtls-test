/*
 *  Copyright (c) 2019-2022, Arm Limited, All Rights Reserved
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may
 *  not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  This file is part of Mbed TLS (https://www.trustedfirmware.org/projects/mbed-tls/)
 */

import java.util.concurrent.Callable

import groovy.transform.Field

import net.sf.json.JSONObject
import hudson.AbortException

import org.mbed.tls.jenkins.BranchInfo

// Keep track of builds that fail.
// Use static field, so the is content preserved across stages.
@Field static failed_builds = [:]

//Record coverage details for reporting
@Field coverage_details = ['coverage': 'Code coverage job did not run']

private Map<String, Callable<Void>> job(String label, Callable<Void> body) {
    return Collections.singletonMap(label, body)
}

private Map<String, Callable<Void>> instrumented_node_job(String node_label, String job_name, Callable<Void> body) {
    return job(job_name) {
        analysis.node_record_timestamps(node_label, job_name, body)
    }
}

Map<String, Callable<Void>> gen_simple_windows_jobs(BranchInfo info, String label, String script) {
    return instrumented_node_job('windows', label) {
        try {
            dir('src') {
                deleteDir()
                checkout_repo.checkout_repo(info)
                timeout(time: common.perJobTimeout.time,
                        unit: common.perJobTimeout.unit) {
                    analysis.record_inner_timestamps('windows', label) {
                        bat script
                    }
                }
            }
        } catch (err) {
            failed_builds[label] = true
            throw (err)
        } finally {
            deleteDir()
        }
    }
}

def node_label_for_platform(platform) {
    switch (platform) {
    case ~/^(debian|ubuntu)(-.*)?/: return 'container-host';
    case 'arm-compilers': return 'container-host';
    case ~/^freebsd(-.*)?/: return 'freebsd';
    case ~/^windows(-.*)?/: return 'windows';
    default: return platform;
    }
}

def platform_has_docker(platform) {
    if (platform == 'arm-compilers') {
        return true
    }
    def os = platform.replaceFirst(/-.*/, "")
    return ['debian', 'ubuntu'].contains(os)
}

def platform_lacks_tls_tools(platform) {
    if (platform == 'arm-compilers') {
        return true
    }
    def os = platform.replaceFirst(/-.*/, "")
    return ['freebsd'].contains(os)
}

def gen_docker_job(BranchInfo info,
                   String job_name,
                   String platform,
                   String script_in_docker,
                   Closure post_checkout=null,
                   Closure post_success=null,
                   Closure post_execution=null) {
    return instrumented_node_job('container-host', job_name) {
        try {
            deleteDir()
            common.get_docker_image(platform)
            dir('src') {
                checkout_repo.checkout_repo(info)
                if (post_checkout) {
                    post_checkout()
                }
                writeFile file: 'steps.sh', text: """\
#!/bin/sh
set -eux
ulimit -f 20971520

if [ -e scripts/min_requirements.py ]; then
scripts/min_requirements.py --user ${info.python_requirements_override_file}
fi
""" + script_in_docker
                sh 'chmod +x steps.sh'
            }
            timeout(time: common.perJobTimeout.time,
                    unit: common.perJobTimeout.unit) {
                try {
                    analysis.record_inner_timestamps('container-host', job_name) {
                        sh common.docker_script(
                                platform, "/var/lib/build/steps.sh"
                        )
                    }
                    if (post_success) {
                        post_success()
                    }
                } finally {
                    dir('src/tests/') {
                        common.archive_zipped_log_files(job_name)
                    }
                }
            }
        } catch (err) {
            failed_builds[job_name] = true
            if (post_execution) {
                post_execution()
            }
            throw (err)
        } finally {
            deleteDir()
        }
    }
}

def gen_all_sh_jobs(BranchInfo info, platform, component, label_prefix='') {
    def shorthands = [
        "arm-compilers": "armcc",
        "ubuntu-16.04": "u16",
        "ubuntu-18.04": "u18",
        "ubuntu-20.04": "u20",
        "ubuntu-22.04": "u22",
        "freebsd": "fbsd",
    ]
    /* Default to the full platform hame is a shorthand is not found */
    def shortplat = shorthands.getOrDefault(platform, platform)
    def job_name = "${label_prefix}all_${shortplat}-${component}"
    def use_docker = platform_has_docker(platform)
    def extra_setup_code = ''
    def node_label = node_label_for_platform(platform)

    if (platform_lacks_tls_tools(platform)) {
        /* The check_tools function in all.sh insists on the existence of the
         * TLS tools, even if no test happens to use them. Passing 'false'
         * pacifies check_tools, but will cause tests to fail if they
         * do try to use it. */
        extra_setup_code += '''
export OPENSSL=false GNUTLS_CLI=false GNUTLS_SERV=false
'''
    }

    if (platform.contains('bsd')) {
        /* At the time of writing, all.sh assumes that make is GNU make.
         * But on FreeBSD, make is BSD make and gmake is GNU make.
         * So put a "make" which is GNU make ahead of the system "make"
         * in $PATH. */
        extra_setup_code += '''
[ -d bin ] || mkdir bin
[ -x bin/make ] || ln -s /usr/local/bin/gmake bin/make
PATH="$PWD/bin:$PATH"
echo >&2 'Note: "make" will run /usr/local/bin/gmake (GNU make)'
'''
        /* At the time of writing, `all.sh test_clang_opt` fails on FreeBSD
         * because it uses `-std=c99 -pedantic` and Clang on FreeBSD
         * thinks that our code is trying to use a C11 feature
         * (static_assert). Which is true, but harmless since our code
         * checks for this feature's availability. As a workaround,
         * instrument the compilation not to treat the use of C11 features
         * as errors, only as warnings.
         * https://github.com/ARMmbed/mbedtls/issues/3693
         */
        extra_setup_code += '''
# We added the bin/ subdirectory to the beginning of $PATH above.
cat >bin/clang <<'EOF'
#!/bin/sh
exec /usr/bin/clang -Wno-error=c11-extensions "$@"
EOF
chmod +x bin/clang
echo >&2 'Note: "clang" will run /usr/bin/clang -Wno-error=c11-extensions'
'''
    }

    if (info.has_min_requirements) {
        extra_setup_code += """
scripts/min_requirements.py --user ${info.python_requirements_override_file}
"""
    }

    return instrumented_node_job(node_label, job_name) {
        try {
            deleteDir()
            if (use_docker) {
                common.get_docker_image(platform)
            }
            dir('src') {
                checkout_repo.checkout_repo(info)
                writeFile file: 'steps.sh', text: """\
#!/bin/sh
set -eux
ulimit -f 20971520
export MBEDTLS_TEST_OUTCOME_FILE='${job_name}-outcome.csv'
${extra_setup_code}
./tests/scripts/all.sh --seed 4 --keep-going $component
"""
                sh 'chmod +x steps.sh'
            }
            timeout(time: common.perJobTimeout.time,
                    unit: common.perJobTimeout.unit) {
                try {
                    if (use_docker) {
                        analysis.record_inner_timestamps(node_label, job_name) {
                            sh common.docker_script(
                                platform, "/var/lib/build/steps.sh"
                            )
                        }
                    } else {
                        dir('src') {
                            analysis.record_inner_timestamps(node_label, job_name) {
                                sh './steps.sh'
                            }
                        }
                    }
                } finally {
                    dir('src') {
                        analysis.stash_outcomes(job_name)
                    }
                    dir('src/tests/') {
                        common.archive_zipped_log_files(job_name)
                    }
                }
            }
        } catch (err) {
            failed_builds[job_name] = true
            throw (err)
        } finally {
            deleteDir()
        }
    }
}

def gen_windows_testing_job(BranchInfo info, String toolchain, String label_prefix='') {
    def prefix = "${label_prefix}Windows-${toolchain}"
    def build_configs, arches, build_systems, retargeted
    if (toolchain == 'mingw') {
        build_configs = ['mingw']
        arches = ['x64']
        build_systems = ['shipped']
        retargeted = [false]
    } else {
        build_configs = ['Release', 'Debug']
        arches = ['Win32', 'x64']
        build_systems = ['shipped', 'cmake']
        retargeted = [false, true]
    }

    // Generate the test configs we will be testing, and tag each with the group they will be executed in
    def test_configs = [build_configs, arches, build_systems, retargeted].combinations().collect { args ->
        def (build_config, arch, build_system, retarget) = args
        def job_name = "$prefix${toolchain == 'mingw' ? '' : "-$build_config-$arch-$build_system${retarget ? '-retarget' : ''}"}"
        /* Sort debug builds using the cmake build system into individual groups, since they are by far the slowest,
         * lumping everything else into a single group per toolchain. This should give us workgroups that take between
         * 15-30 minutes to execute. */
        def group = build_config == 'Debug' &&  build_system == 'cmake' ? job_name : prefix
        return [
            group:       group,
            job_name:    job_name,
            test_config: [
                visual_studio_configurations:    [build_config],
                visual_studio_architectures:     [arch],
                visual_studio_solution_types:    [build_system],
                visual_studio_retarget_solution: [retarget],
            ],
        ]
    }

    // Return one job per workgroup
    return test_configs.groupBy({ item -> (String) item.group }).collectEntries { group, items ->
        return instrumented_node_job('windows', group) {
            try {
                stage('checkout') {
                    dir("src") {
                        deleteDir()
                        checkout_repo.checkout_repo(info)
                    }
                    /* The empty files are created to re-create the directory after it
                     * and its contents have been removed by deleteDir. */
                    dir("logs") {
                        deleteDir()
                        writeFile file: '_do_not_delete_this_directory.txt', text: ''
                    }

                    dir("worktrees") {
                        deleteDir()
                        writeFile file: '_do_not_delete_this_directory.txt', text: ''
                    }

                    if (info.has_min_requirements) {
                        dir("src") {
                            timeout(time: common.perJobTimeout.time,
                                unit: common.perJobTimeout.unit) {
                                bat "python scripts\\min_requirements.py ${info.python_requirements_override_file}"
                            }
                        }
                    }

                    /* libraryResource loads the file as a string. This is then
                     * written to a file so that it can be run on a node. */
                    def windows_testing = libraryResource 'windows/windows_testing.py'
                    writeFile file: 'windows_testing.py', text: windows_testing
                }

                analysis.record_inner_timestamps('windows', group) {
                    /* Execute each test in a workgroup serially. If any exceptions are thrown store them, and continue
                     * with the next test. This replicates the preexisting behaviour windows_testing.py and
                     * jobs.failFast = false */
                    def exceptions = items.findResults { item ->
                        def job_name = (String) item.job_name
                        try {
                            stage(job_name) {
                                common.report_errors(job_name) {
                                    def extra_args = ''
                                    if (toolchain != 'mingw') {
                                        writeFile file: 'test_config.json', text: JSONObject.fromObject(item.test_config).toString()
                                        extra_args = '-c test_config.json'
                                    }

                                    timeout(time: common.perJobTimeout.time + common.perJobTimeout.windowsTestingOffset,
                                        unit: common.perJobTimeout.unit) {
                                        bat "python windows_testing.py src logs $extra_args -b $toolchain"
                                    }
                                }
                            }
                        } catch (exception) {
                            failed_builds[job_name] = true
                            return exception
                        }
                        return null
                    }
                    // If we collected any exceptions, throw the first one
                    if (exceptions.size() > 0) {
                        throw exceptions.first()
                    }
                }
            } finally {
                deleteDir()
            }
        }
    }
}

def gen_windows_jobs(BranchInfo info, String label_prefix='') {
    String preamble = ''
    if (info.has_min_requirements) {
        preamble += "python scripts\\min_requirements.py ${info.python_requirements_override_file} || exit\r\n"
    }

    def jobs = [:]
    jobs = jobs + gen_simple_windows_jobs(
        info, label_prefix + 'win32-mingw',
        preamble + scripts.win32_mingw_test_bat
    )
    jobs = jobs + gen_simple_windows_jobs(
        info, label_prefix + 'win32_msvc12_32',
        preamble + scripts.win32_msvc12_32_test_bat
    )
    jobs = jobs + gen_simple_windows_jobs(
        info, label_prefix + 'win32-msvc12_64',
        preamble + scripts.win32_msvc12_64_test_bat
    )
    for (build in common.get_supported_windows_builds()) {
        jobs = jobs + gen_windows_testing_job(info, build, label_prefix)
    }
    return jobs
}

def gen_abi_api_checking_job(BranchInfo info, String platform) {
    def job_name = 'ABI-API-checking'
    def script_in_docker = '''
tests/scripts/list-identifiers.sh --internal
scripts/abi_check.py -o FETCH_HEAD -n HEAD -s identifiers --brief
'''

    def credentials_id = common.is_open_ci_env ? "mbedtls-github-ssh" : "742b7080-e1cc-41c6-bf55-efb72013bc28"
    def post_checkout = {
        /* The credentials here are the SSH credentials for accessing the repositories.
           They are defined at {JENKINS_URL}/credentials */
        withCredentials([sshUserPrivateKey(credentialsId: credentials_id, keyFileVariable: 'keyfile')]) {
            sh "GIT_SSH_COMMAND=\"ssh -i ${keyfile}\" git fetch origin ${CHANGE_TARGET}"
        }
    }

    return gen_docker_job(info, job_name, platform, script_in_docker,
                          post_checkout=post_checkout)
}

def gen_code_coverage_job(BranchInfo info, String platform) {
    def job_name = 'code-coverage'
    def script_in_docker = '''
if grep -q -F coverage-summary.txt tests/scripts/basic-build-test.sh; then
# New basic-build-test, generates coverage-summary.txt
./tests/scripts/basic-build-test.sh
else
# Old basic-build-test, only prints the coverage summary to stdout
{ stdbuf -oL ./tests/scripts/basic-build-test.sh 2>&1; echo $?; } |
  tee basic-build-test.log
[ "$(tail -n1 basic-build-test.log)" -eq 0 ]
sed -n '/^Test Report Summary/,$p' basic-build-test.log >coverage-summary.txt
rm basic-build-test.log
fi
'''

    def post_success = {
        dir('src') {
            String coverage_log = readFile('coverage-summary.txt')
            coverage_details['coverage'] = coverage_log.substring(
                coverage_log.indexOf('\nCoverage\n') + 1
            )
        }
    }

    return gen_docker_job(info, job_name, platform, script_in_docker,
                          post_success=post_success)
}

/* Mbed OS Example job generation */
def gen_all_example_jobs() {
    def jobs = [:]

    examples.examples.each { example ->
        if (example.value['should_run'] == 'true') {
            for (compiler in example.value['compilers']) {
                for (platform in example.value['platforms']()) {
                    if (examples.raas_for_platform[platform]) {
                        jobs = jobs + gen_mbed_os_example_job(
                            example.value['repo'],
                            example.value['branch'],
                            example.key, compiler, platform,
                            examples.raas_for_platform[platform]
                        )
                    }
                }
            }
        }
    }
    return jobs
}

def gen_mbed_os_example_job(repo, branch, example, compiler, platform, raas) {
    def jobs = [:]
    def job_name = "mbed-os-${example}-${platform}-${compiler}"

    return instrumented_node_job(compiler, job_name) {
        try {
            deleteDir()
/* Create python virtual environment and install mbed tools */
            sh """\
ulimit -f 20971520
virtualenv $WORKSPACE/mbed-venv
. $WORKSPACE/mbed-venv/bin/activate
pip install mbed-cli
pip install mbed-host-tests
"""
            dir('mbed-os-example') {
                deleteDir()
                checkout_repo.checkout_mbed_os_example_repo(repo, branch)
                dir(example) {
/* If the job is targeting an example repo, then we wish to use the versions
* of Mbed OS, TLS and Crypto specified by the mbed-os.lib file. */
                    if (env.TARGET_REPO == 'example') {
                        sh """\
ulimit -f 20971520
. $WORKSPACE/mbed-venv/bin/activate
mbed config root .
mbed deploy -vv
"""
                    } else {
/* If the job isn't targeting an example repo, the versions of Mbed OS, TLS and
* Crypto will be specified by the job. We remove mbed-os.lib so we aren't
* checking it out twice. Mbed deploy is still run in case other libraries
* are required to be deployed. We then check out Mbed OS, TLS and Crypto
* according to the job parameters. */
                        sh """\
ulimit -f 20971520
. $WORKSPACE/mbed-venv/bin/activate
rm -f mbed-os.lib
mbed config root .
mbed deploy -vv
"""
                        dir('mbed-os') {
                            deleteDir()
                            checkout_repo.checkout_mbed_os()
/* Check that python requirements are up to date */
                            sh """\
ulimit -f 20971520
. $WORKSPACE/mbed-venv/bin/activate
pip install -r requirements.txt
"""
                        }
                    }
                    timeout(time: common.perJobTimeout.time +
                                  common.perJobTimeout.raasOffset,
                            unit: common.perJobTimeout.unit) {
                        def tag_filter = ""
                        if (example == 'atecc608a') {
                            tag_filter = "--tag-filters HAS_CRYPTOKIT"
                        }
                        sh """\
ulimit -f 20971520
. $WORKSPACE/mbed-venv/bin/activate
mbed compile -m ${platform} -t ${compiler}
"""
                        for (int attempt = 1; attempt <= 3; attempt++) {
                            try {
                                sh """\
ulimit -f 20971520
if [ -e BUILD/${platform}/${compiler}/${example}.bin ]
then
BINARY=BUILD/${platform}/${compiler}/${example}.bin
else
if [ -e BUILD/${platform}/${compiler}/${example}.hex ]
then
    BINARY=BUILD/${platform}/${compiler}/${example}.hex
fi
fi

export RAAS_PYCLIENT_FORCE_REMOTE_ALLOCATION=1
export RAAS_PYCLIENT_ALLOCATION_QUEUE_TIMEOUT=3600
mbedhtrun -m ${platform} ${tag_filter} \
-g raas_client:https://${raas}.mbedcloudtesting.com:443 -P 1000 --sync=0 -v \
--compare-log ../tests/${example}.log -f \$BINARY
"""
                                break
                            } catch (AbortException err) {
                                if (attempt == 3) throw (err)
                            }
                        }
                    }
                }
            }
        } catch (err) {
            failed_builds[job_name] = true
            throw (err)
        } finally {
            deleteDir()
        }
    }
}

def gen_coverity_push_jobs() {
    def jobs = [:]
    def job_name = 'coverity-push'

    if (env.MBED_TLS_BRANCH == "development") {
        jobs << instrumented_node_job('container-host', job_name) {
            try {
                dir("src") {
                    deleteDir()
                    checkout_repo.checkout_repo()
                    sshagent([env.GIT_CREDENTIALS_ID]) {
                        analysis.record_inner_timestamps('container-host', job_name) {
                            sh 'git push origin HEAD:coverity_scan'
                        }
                    }
                }
            } catch (err) {
                failed_builds[job_name]= true
                throw (err)
            } finally {
                deleteDir()
            }
        }
    }

    return jobs
}

def gen_release_jobs(BranchInfo info, String label_prefix='', boolean run_examples=true) {
    def jobs = [:]

    if (env.RUN_BASIC_BUILD_TEST == "true") {
        jobs = jobs + gen_code_coverage_job(info, 'ubuntu-16.04');
    }

    if (env.RUN_ALL_SH == "true") {
        info.all_all_sh_components.each({component, platform ->
            jobs = jobs + gen_all_sh_jobs(info, platform, component, label_prefix)
        })
    }

    /* FreeBSD all.sh jobs */
    if (env.RUN_FREEBSD == "true") {
        for (platform in common.bsd_platforms) {
            for (component in common.freebsd_all_sh_components) {
                jobs = jobs + gen_all_sh_jobs(info, platform, component, label_prefix)
            }
        }
    }

    if (env.RUN_WINDOWS_TEST == "true") {
        jobs = jobs + gen_windows_jobs(info, label_prefix)
    }

    if (run_examples) {
        jobs = jobs + gen_all_example_jobs()
    }

    if (env.PUSH_COVERITY == "true") {
        jobs = jobs + gen_coverity_push_jobs()
    }

    return jobs
}

def gen_dockerfile_builder_job(String platform, boolean overwrite=false) {
    def dockerfile = libraryResource "docker_files/$platform/Dockerfile"

    def tag = "$platform-${common.git_hash_object(dockerfile)}"
    def check_docker_image
    if (common.is_open_ci_env) {
        check_docker_image = "docker manifest inspect $common.docker_repo:$tag > /dev/null 2>&1"
    } else {
        check_docker_image = "aws ecr describe-images --repository-name $common.docker_repo_name --image-ids imageTag=$tag"
    }

    common.docker_tags[platform] = tag

    return job(platform) {
        /* Take the lock on the master node, so we don't tie up an executor while waiting */
        lock(tag) {
            analysis.node_record_timestamps('dockerfile-builder', platform) {
                def image_exists = false
                if (!overwrite) {
                    image_exists = sh(script: check_docker_image, returnStatus: true) == 0
                }
                if (overwrite || !image_exists) {
                    dir('docker') {
                        deleteDir()
                        writeFile file: 'Dockerfile', text: dockerfile
                        def extra_build_args = ''

                        if (common.is_open_ci_env) {
                            extra_build_args = '--build-arg ARMLMD_LICENSE_FILE=27000@flexnet.trustedfirmware.org'

                            withCredentials([string(credentialsId: 'DOCKER_AUTH', variable: 'TOKEN')]) {
                                sh """\
mkdir -p ${env.HOME}/.docker
cat > ${env.HOME}/.docker/config.json << EOF
{
        "auths": {
                "https://index.docker.io/v1/": {
                        "auth": "\${TOKEN}"
                }
        }
}
EOF
chmod 0600 ${env.HOME}/.docker/config.json
"""
                            }
                        } else {
                            sh """\
aws ecr get-login-password | docker login --username AWS --password-stdin $common.docker_ecr
"""
                        }

                        analysis.record_inner_timestamps('dockerfile-builder', platform) {
                            sh """\
# Use BuildKit and a remote build cache to pull only the reuseable layers
# from the last successful build for this platform
DOCKER_BUILDKIT=1 docker build \
    --build-arg BUILDKIT_INLINE_CACHE=1 \
    --build-arg DOCKER_REPO=$common.docker_repo \
    $extra_build_args \
    --cache-from $common.docker_repo:$platform-cache \
    -t $common.docker_repo:$tag \
    -t $common.docker_repo:$platform-cache .

# Push the image with its unique tag, as well as the build cache tag
docker push $common.docker_repo:$tag
docker push $common.docker_repo:$platform-cache
"""
                        }
                    }
                }
            }
        }
    }
}
