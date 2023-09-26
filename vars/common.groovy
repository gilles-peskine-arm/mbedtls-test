/* Miscellaneous constants and helper functions
 *
 * Do not define mutable variables as fields! A Groovy module can be
 * instantiated more than once and each instance has its own copy of
 * the file-scope variables. It's ok to have variables that are
 * initialized dynamically (for example, from environment variables)
 * but you need to make sure that the variable will always end up with
 * the same value in a given run.
 */

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

import java.security.MessageDigest

import groovy.transform.Field

import com.cloudbees.groovy.cps.NonCPS
import hudson.AbortException

/* Indicates if CI is running on Open CI (hosted on https://ci.trustedfirmware.org/) */
@Field is_open_ci_env = env.JENKINS_URL ==~ /\S+(trustedfirmware)\S+/

/*
 * This controls the timeout each job has. It does not count the time spent in
 * waiting queues and setting up the environment.
 *
 * Raas has its own resource queue with the timeout of 1000s, we need to take
 * it into account for the on-target test jobs.
 */
@Field perJobTimeout = [
        time: 120,
        raasOffset: 17,
        windowsTestingOffset: 0,
        unit: 'MINUTES'
]

@Field compiler_paths = [
    'gcc' : 'gcc',
    'gcc48' : '/usr/local/bin/gcc48',
    'clang' : 'clang',
    'cc' : 'cc'
]

@Field docker_repo_name = is_open_ci_env ? 'ci-amd64-mbed-tls-ubuntu' : 'jenkins-mbedtls'
@Field docker_ecr = is_open_ci_env ? "trustedfirmware" : "666618195821.dkr.ecr.eu-west-1.amazonaws.com"
@Field docker_repo = "$docker_ecr/$docker_repo_name"

/* List of Linux platforms. When a job can run on multiple Linux platforms,
 * it runs on the first element of the list that supports this job. */
@Field linux_platforms = [
    "ubuntu-16.04", "ubuntu-18.04", "ubuntu-20.04", "ubuntu-22.04",
    "arm-compilers",
]
/* List of BSD platforms. They all run freebsd_all_sh_components. */
@Field bsd_platforms = ["freebsd"]

@Field freebsd_all_sh_components = [
    /* Do not include any components that do TLS system testing, because
     * we don't maintain suitable versions of OpenSSL and GnuTLS on
     * secondary platforms. */
    'test_default_out_of_box',          // out of box, make
    /* FreeBSD on the CI doesn't have gcc. */
    'test_clang_opt',                   // clang, make
    //'test_gcc_opt',                     // gcc, make
    'test_cmake_shared',                // cmake
    'test_cmake_out_of_source',         // cmake
]

/* Maps platform names to the tag of the docker image used to test that platform.
 * Populated by init_docker_images() / gen_jobs.gen_dockerfile_builder_job(platform). */
@Field static docker_tags = [:]


class BranchInfo {
    /* Map from component name to chosen platform to run it, or to null
     * if no platform has been chosen yet. */
    public Map<String, String> all_all_sh_components

    /* Whether scripts/min_requirements.py is available. Older branches don't
     * have it, so they only get what's hard-coded in the docker files on Linux,
     * and bare python on other platforms. */
    public boolean has_min_requirements

    /* Ad hoc overrides for scripts/ci.requirements.txt, used to adjust
     * requirements on older branches that broke due to updates of the
     * required packages.
     * Only used if has_min_requirements is true. */
    public String python_requirements_override_content

    /* Name of the file containing python_requirements_override_content.
     * The string is injected into Unix sh and Windows cmd command lines,
     * so it must not contain any shell escapes or directory separators.
     * Only used if has_min_requirements is true.
     * Set to an empty string for convenience if no override is to be
     * done. */
    public String python_requirements_override_file

    BranchInfo() {
        this.all_all_sh_components = [:]
        this.has_min_requirements = false
        this.python_requirements_override_content = ''
        this.python_requirements_override_file = ''
    }
}

/* Compute the git object ID of the Dockerfile.
* Equivalent to the `git hash-object <file>` command. */
@NonCPS
def git_hash_object(str) {
    def sha1 = MessageDigest.getInstance('SHA1')
    sha1.update("blob ${str.length()}\0".bytes)
    def digest = sha1.digest(str.bytes)
    return String.format('%040x', new BigInteger(1, digest))
}


def get_docker_tag(platform) {
    def tag = docker_tags[platform]
    if (tag == null)
        throw new NoSuchElementException(platform)
    else
        return tag
}

@NonCPS
static String stack_trace_to_string(Throwable t) {
    StringWriter writer = new StringWriter()
    PrintWriter printWriter = new PrintWriter(writer)
    t.printStackTrace(printWriter)
    printWriter.close()
    return writer.toString()
}

Map wrap_report_errors(Map jobs) {
    return jobs.collectEntries { name, job ->
        [(name): {
            try {
                job()
            } catch (err) {
                echo """\
Failed job: $name
Caught: ${stack_trace_to_string(err)}
"""
                if (!currentBuild.resultIsWorseOrEqualTo('FAILURE')) {
                    currentBuild.result = 'FAILURE'
                    maybe_notify_github('FAILURE', "Failures: ${name}…")
                }
                throw err
            }
        }]
    }
}


String construct_python_requirements_override() {
    List<String> overrides = []

    if (overrides) {
        List<String> header = ['-r scripts/ci.requirements.txt']
        List<String> footer = [''] // to get a trailing newline
        return (header + overrides + footer).join('\n')
    } else {
        return ''
    }
}


def init_docker_images() {
    stage('init-docker-images') {
        def jobs = wrap_report_errors(linux_platforms.collectEntries {
            platform -> gen_jobs.gen_dockerfile_builder_job(platform)
        })
        jobs.failFast = false
        parallel jobs
    }
}

def get_docker_image(platform) {
    def docker_image = get_docker_tag(platform)
    for (int attempt = 1; attempt <= 3; attempt++) {
        try {
            if (is_open_ci_env)
                sh """\
docker pull $docker_repo:$docker_image
"""
            else
                sh """\
aws ecr get-login-password | docker login --username AWS --password-stdin $docker_ecr
docker pull $docker_repo:$docker_image
"""
            break
        } catch (AbortException err) {
            if (attempt == 3) throw (err)
        }
    }
}

def docker_script(platform, entrypoint, entrypoint_arguments='') {
    def docker_image = get_docker_tag(platform)
    return """\
docker run -u \$(id -u):\$(id -g) -e MAKEFLAGS --rm --entrypoint $entrypoint \
    -w /var/lib/build -v `pwd`/src:/var/lib/build \
    --cap-add SYS_PTRACE $docker_repo:$docker_image $entrypoint_arguments
"""
}

/* Gather information about the branch that determines how to set up the
 * test environment.
 * In particular, get components of all.sh for Linux platforms. */
def get_branch_information() {
    node('container-host') {
        BranchInfo info = new BranchInfo()

        dir('src') {
            deleteDir()
            checkout_repo.checkout_repo()

            info.has_min_requirements = fileExists('scripts/min_requirements.py')

            if (info.has_min_requirements) {
                info.python_requirements_override_content = construct_python_requirements_override()
                if (info.python_requirements_override_content) {
                    info.python_requirements_override_file = 'override.requirements.txt'
                }
            }

            // Branches written in C89 (plus very minor extensions) have
            // "-Wdeclaration-after-statement" in CMakeLists.txt, so look
            // for that to determine whether the code is supposed to be C89.
            String cmakelists_contents = readFile('CMakeLists.txt')
            code_is_c89 = cmakelists_contents.contains('-Wdeclaration-after-statement')
        }

        // Log the environment for debugging purposes
        sh script: 'export'

        for (platform in linux_platforms) {
            get_docker_image(platform)
            def all_sh_help = sh(
                script: docker_script(
                    platform, "./tests/scripts/all.sh", "--help"
                ),
                returnStdout: true
            )
            if (all_sh_help.contains('list-components')) {
                if (!info.all_all_sh_components) {
                    def all = sh(
                        script: docker_script(
                            platform, "./tests/scripts/all.sh",
                            "--list-all-components"
                        ),
                        returnStdout: true
                    ).trim().split('\n')
                    for (element in all) {
                        info.all_all_sh_components[element] = null
                    }
                }
                def available = sh(
                    script: docker_script(
                        platform, "./tests/scripts/all.sh", "--list-components"
                    ),
                    returnStdout: true
                ).trim().split('\n')
                echo "Available all.sh components on ${platform}: ${available.join(" ")}"
                for (element in available) {
                    if (!info.all_all_sh_components[element]) {
                        info.all_all_sh_components[element] = platform
                    }
                }
            } else {
                error('Pre Test Checks failed: Base branch out of date. Please rebase')
            }
        }

        /* Temporary ad hoc assignment: at the time of writing,
         * all.sh does not detect the presence of arm compilers, so
         * it always reports build_armcc as available. Until
         * https://github.com/Mbed-TLS/mbedtls/pull/7163 is merged
         * and we no longer care about older pull requests, choose
         * its dispatch manually.
         */
        info.all_all_sh_components['build_armcc'] = 'arm-compilers'
        echo "Overriding all_all_sh_components['build_armcc'] = 'arm-compilers'"

        return info
    }
}

def check_every_all_sh_component_will_be_run(BranchInfo info) {
    def untested_all_sh_components = info.all_all_sh_components.collectMany(
        {name, platform -> platform ? [] : [name]})
    if (untested_all_sh_components != []) {
        error(
            "Pre-test checks failed: Unable to run all.sh components: \
            ${untested_all_sh_components.join(",")}"
        )
    }
}

def get_supported_windows_builds() {
    def vs_builds = []
    if (env.JOB_TYPE == 'PR') {
        vs_builds = ['2013']
    } else {
        vs_builds = ['2013', '2015', '2017']
    }
    if (code_is_c89) {
        vs_builds = ['2010'] + vs_builds
    }
    echo "vs_builds = ${vs_builds}"
    return ['mingw'] + vs_builds
}

/* In the PR job (recognized because we set the BRANCH_NAME environment
 * variable), report an additional context to GitHub.
 * Do nothing from a job that isn't triggered from GitHub.
 *
 * state: one of 'PENDING', 'SUCCESS' or 'FAILURE' (case-insensitive).
 *        Contexts used in a CI job should be marked as PENDING at the
 *        beginning of job and as SUCCESS or FAILURE once the outcome is known.
 * description: a free-form description shown next to the state. It is
 *              truncated to 140 characters (GitHub limitation).
 * context (optional): a short string identifying which part of the job this is
 *                     a status for. GitHub only shows the latest state and
 *                     description for a given context. If it is omitted, this
 *                     method determines the correct context from is_open_ci_env
 *                     and BRANCH_NAME.
 */
void maybe_notify_github(String state, String description, String context=null) {
    if (!env.BRANCH_NAME) {
        return;
    }

    /* Truncate the description. Otherwise githubNotify fails. */
    final MAX_DESCRIPTION_LENGTH = 140
    if (description.length() > MAX_DESCRIPTION_LENGTH) {
        description = description.take(MAX_DESCRIPTION_LENGTH - 1) + '…'
    }

    if (context == null) {
        def ci = is_open_ci_env ? 'TF OpenCI' : 'Internal CI'
        def job = env.BRANCH_NAME ==~ /PR-\d+-merge/ ? 'Interface stability tests' : 'PR tests'
        context = "$ci: $job"
    }

    githubNotify context: context,
                 status: state,
                 description: description,
                /* Set owner and repository explicitly in case the multibranch pipeline uses multiple repos
-                * Needed for testing Github merge queues */
                 account: env.GITHUB_ORG,
                 repo: env.GITHUB_REPO
}

def archive_zipped_log_files(job_name) {
    sh """\
for i in *.log; do
    [ -f "\$i" ] || break
    mv "\$i" "$job_name-\$i"
    xz "$job_name-\$i"
done
"""
    archiveArtifacts(
        artifacts: '*.log.xz',
        fingerprint: true,
        allowEmptyArchive: true
    )
}

def send_email(name, branch, failed_builds, coverage_details) {
    if (failed_builds) {
        keys = failed_builds.keySet()
        failures = keys.join(", ")
        emailbody = """
${coverage_details['coverage']}

Logs: ${env.BUILD_URL}

Failures: ${failures}
"""
        recipients = env.TEST_FAIL_EMAIL_ADDRESS
    } else {
        emailbody = """
${coverage_details['coverage']}

Logs: ${env.BUILD_URL}
"""
        recipients = env.TEST_PASS_EMAIL_ADDRESS
    }
    subject = ((is_open_ci_env ? "TF Open CI" : "Internal CI") + " ${name} " + \
           (failed_builds ? "failed" : "passed") + "! (branch: ${branch})")
    echo subject
    echo emailbody
    emailext body: emailbody,
             subject: subject,
             to: recipients,
             mimeType: 'text/plain'
}
