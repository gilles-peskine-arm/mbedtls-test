Mbed TLS continuous integration (CI) scripts developer's guide
==============================================================

This document offers some guidance to developers of the Mbed TLS continuous integration (CI) scripts. It is intended for Mbed TLS maintainers. The Mbed TLS CI scripts are not intended for external contributions, and many processes in this document are only available to TrustedFirmware members or Arm employees.

## Overview of the Mbed TLS CI

The Mbed TLS CI is expressed as [Jenkins](https://www.jenkins.io/) pipelines written in [Groovy](https://groovy-lang.org/).

The [`mbedtls-test` repository](https://github.com/Mbed-TLS/mbedtls-test) contains:

* Groovy pipeline scripts under [`vars`](vars/).
* Docker files used for testing on Linux under [`resources/docker_files`](resources/docker_files/).
* A script used for testing on Windows: [`resources/windows/windows_testing.py`](resources/windows/windows_testing.py).

### Jenkins instances

At the time of writing, there are two instances of Jenkins:

* [OpenCI](https://mbedtls.trustedfirmware.org/), maintained by Linaro on behalf of TrustedFirmware. The OpenCI instance is public. Only Mbed TLS team members can have accounts (access is via [the `trusted-firmware-mbed-tls-openci-users` team in `trusted-firmware-ci` on GitHub](https://github.com/orgs/trusted-firmware-ci/teams/trusted-firmware-mbed-tls-openci-users/members)), but everyone can see test results.
* [Arm Internal CI](https://jenkins-mbedtls.oss.arm.com/) (private link), maintained by Arm. This instance is only accessible to Arm employees.

The two instances mostly have the same capabilities, but they can differ in terms of Jenkins versions, available plugins, OS versions, etc.

## General programming advice

### Compatibility with `mbedtls`

The scripts in `mbedtls-test` must work with:

* The `development` branch of Mbed TLS.
* The `master` branch of Mbed TLS (which only contains releases).
* The `main` branch of the PSA Crypto implementation (work in progress).
* The latest release, in case we need to issue a patch release.
* Long-time support branches.
* Pull requests targeting one of the above, and more generally branches forked from the above.

Note in particular that `mbedtls-test` must support branches that are somewhat out of date, to avoid disrupting ongoing work. An active pull request that passed the CI at some point should generally not fail due to an upgrade of `mbedtls-test`. The definition of “active” can vary, but generally we want to preserve compatibility for at least a few months, and in any case we want to preserve compatibility with the last release in each maintained branch.

The code in `mbedtls-test` knows what branch to test because it is passed as environment variables. The environment variables point to a Git repository and a branch name.

#### Interface transitioning

If you want to change the interface between `mbedtls` and `mbedtls-test`, you need to proceed with caution. This interface is not documented, but crucial to having practical working CI. Any incompatible change must be done gradually.

If you add tests in `mbedtls` that require a new tool on the CI:

1. Make the new tool available. If the tool runs on Linux, add it to the Docker image(s) via a pull request on `mbedtls-test`. If the tool doesn't run on Linux, this will require a request to the devops teams that manage the two Jenkins instances.
2. Make a pull request in `mbedtls` that starts using the new tool.

If you add a new entry point in `mbedtls` that CI code should invoke:

1. Open a pull request in `mbedtls` that adds the new entry point. Label it “DO NOT MERGE” for the time being.
2. Open a pull request in `mbedtls-test` that adds code that checks whether the new entry point is present, and runs it if present.
3. Test the `mbedtls-test` code both against `development` (at least) and against the branch of your `mbedtls` pull request.
4. Merge the `mbedtls-test` pull request (once tested and approved).
5. Trigger a new CI run on the `mbedtls` pull request. If that passes (and the pull request is approved), the pull request can be merged.

What goes for `mbedtls` also goes for other repositories tested by `mbedtls-test`, in particular [TF-PSA-Crypto](https://github.com/Mbed-TLS/TF-PSA-Crypto).

## Groovy scripts

### Groovy entry points

The entry points for the Groovy code are scripts in the [`vars`](vars/) directory.

* Release/nightly jobs invoke [`vars/mbedtls-release-Jenkinsfile`](vars/mbedtls-release-Jenkinsfile).
* Pull request (“pr-head” and “pr-merge”) jobs invoke [`mbedtls.run_job()`](vars/mbedtls.groovy). The pr-merge job only runs the “Interface stability tests” (formerly known as “ABI-API-check”). The pr-head job runs a full test campaign, with reduced Windows testing, and minus the test coverage job (`basic-build-test.sh`).

### Jenkins pipeline structure

Jenkins runs a [pipeline](https://www.jenkins.io/doc/book/pipeline/), which is expressed as a series of stages which can themselves have sub-stages executed in parallel or serially. We use [scripted pipelines](https://www.jenkins.io/doc/book/pipeline/#scripted-pipeline-fundamentals).

At runtime, the general structure of the pipeline for a release or PR job is:

1. Set up the Docker images (normally from a cache, but they will be rebuilt if needed).
2. Obtain some information about the branch to test. In particular, run `tests/scripts/all.sh --list-all-components` from the tested branch, as well as `tests/scripts/all.sh --list-components` in each Docker container to determine which one to use in the next step.
3. Run all the components to test in parallel. The components consist of:
    * A full run of `all.sh` (spread over multiple Linux versions), invoked by `gen_jobs.gen_all_sh_jobs`.
    * Selected `all.sh` components on FreeBSD (the selection is in `common.freebsd_all_sh_components`).
    * Ad hoc Windows jobs from `scripts.groovy`, invoked by `gen_jobs.gen_windows_jobs`.
    * One or more runs of `resources/windows/windows_testing.py`, invoked by `gen_jobs.gen_windows_testing_job`. The set of runs is determined by `common.get_supported_windows_builds`.
    * A test coverage job (`basic-build-test.sh`). Omitted in the pull request job.
4. Run result analysis (`analysis.analyze_results`). This runs `tests/scripts/analyze_outcomes.py` from the tested branch.

### Miscellaneous Jenkins APIs

#### Build causes

Confused about build causes? Read [Bence's guide](https://github.com/Mbed-TLS/mbedtls-test/pull/129/files#r1348764797).

### Groovy coding tips

#### Available library functions

The Groovy language gives access to the Java standard library. However, on Jenkins, our code runs in a sandbox that blocks large parts of the library.

Jenkins (with the plugins we have installed) makes some extra functions available, in particular [pipeline steps](https://www.jenkins.io/doc/pipeline/steps/workflow-basic-steps/).

#### Global variables

Note that Groovy does not have global variables as such. Each module (`*.groovy` file) is a class, and that class can have multiple instances. Therefore, avoid using script-scope variables in a Groovy module that is loaded from another module. There's existing code that does this, but it's fragile and has caused us headaches so we are moving away from that.

### Playing well with Jenkins

#### Where your code runs

The entry point of the pipeline runs on the Jenkins master node. Because all jobs start on this node, we should not do much on the master node. In particular, we don't check out the code to test on the master. All computation-heavy or I/O-heavy processing must be performed on an executor:

```
node (label) {
    // IO-heavy or computation-heavy code
}
```

The label identifies what features the executor needs to have. In particular, this encodes the operating system. We use three labels:

* `container-host`, which runs Linux and has Docker. Most of our Linux code runs in Docker containers.
* `freebsd`
* `windows`

## Docker images

Most of our Linux testing happens in Docker containers.

### Using Docker locally

See [`resources/docker_files/README.md`](resources/docker_files/README.md).

### Docker container selection

For each `all.sh` component, the Groovy code selects one of the Docker containers that supports that component, based on running `all.sh --list-components` inside that Docker image.

## Validating changes

There is no continuous integration on the `mbedtls-test` repository (except a DCO check for the rare external contributions). Therefore, whenever you change the code, you must run some test jobs manually. What to run depends on what you're changing.

As discussed in [“Versioning”](#versioning), remember that the `mbedtls-test` repository must work not only with `development`, but also with LTS branches and with older branches.

### Validation tools

To validate changes, first upload your changes to a branch in the `mbedtls-test` repository. (Forks are not supported.) Use your personal namespace, i.e. branches called `dev/${your_github_username}/${some_meaningful_name}`. There are two test jobs that cover the common cases:

* [`mbedtls-release-ci-testing`](https://mbedtls.trustedfirmware.org/job/mbedtls-release-ci-testing/): runs a full CI with a chosen branch of `mbedtls-test` on a chosen commit from any repository. Note that in addition to selecting your `mbedtls-test` branch in the dropdown, you need to check one or more of the boxes selecting what will run (`RUN_xxx` variables), otherwise not much will happen.
* [`mbed-tls-restricted-pr-test-parametrized`](https://mbedtls.trustedfirmware.org/job/mbed-tls-restricted-pr-test-parametrized/): runs the PR tests. Useful for what the release job doesn't cover — mainly “Interface stability tests” (formerly known as “ABI-API-check”).

For things that aren't covered, such as GitHub or email reporting, ask an expert.

There are similar jobs on the internal CI.

### Validation tips

#### Validating Dockerfile changes

After changing Dockerfiles, make sure to run at least one test job on each Jenkins instance (OpenCI and Arm internal). Each does its own build of the Docker images, so sometimes things can go wrong only on one side (e.g. due to network accessibility or to the host kernel version).

If you remove anything, make sure to test with LTS branches. Usually we don't reduce test requirements between major releases, so if test tools are good enough for `development`, they're also good enough for older branches targeting `development` or previous minor releases. But a tool might be used e.g. for 2.28 even if it's unused after 3.0.

#### Validating Groovy changes

Groovy is, for practical purposes, an interpreted language. Things like undefined variables may not be detected until the block of code referencing that variable is executed. As a consequence, test your code even after small changes — there's no compiler to tell you that you misspelled a variable.

#### Validating error reporting

If you make changes that affect error reporting, make sure that failures are still caught properly. We don't want to accidentally make a change that is fine if the tests pass, but hide failures!

There are pull requests for testing various kinds of failures in the [`mbedtls-restricted` repository](https://github.com/Mbed-TLS/mbedtls-restricted/labels/ci-testing) (private link). See [“CI testing: development, good”](https://github.com/Mbed-TLS/mbedtls-restricted/pull/906) for more information.
