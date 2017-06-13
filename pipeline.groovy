/*******************************************************************************

    Utils

*******************************************************************************/

/**
    Standard function to be used to do a git checkout for arbitrary URL in
    current folder. Cleans the folder (using `git reset --hard` +
    `git clean -fdx`) in the process.
 **/
def clone (repo_url, git_ref = "master") {
    checkout(
        poll: false,
        scm: [
            $class: 'GitSCM',
            branches: [[name: git_ref]],
            extensions: [[$class: 'CleanBeforeCheckout']],
            userRemoteConfigs: [[url: repo_url]]
        ]
    )
}

/**
    Function to checkout upstream that has triggerred current
    pipeline, for example PR branch. For PRs this will already merge
    with the base branch.

    Requires administrator approval for allow access to:

        method hudson.plugins.git.GitSCM getBranches
        method hudson.plugins.git.GitSCMBackwardCompatibility getExtensions
        method hudson.plugins.git.GitSCM getUserRemoteConfigs
 **/
def cloneUpstream () {
    checkout(scm: [
        $class: 'GitSCM',
        branches: scm.branches,
        extensions: scm.extensions + [[$class: 'CleanBeforeCheckout']],
        userRemoteConfigs: scm.userRemoteConfigs
    ])
}

/**
    Checks out latest SemVer-compatible tag available in specified repo
 **/
def cloneLatestTag (repo_url) {
    checkout(
        poll: false,
        scm: [
            $class: 'GitSCM',
            branches: [[name: "master"]],
            extensions: [[$class: 'CleanBeforeCheckout']],
            userRemoteConfigs: [[url: repo_url]]
        ]
    )

    def LATEST = sh (
        script: 'git tag -l | egrep "^v?[0-9]+\\.[0-9]+\\.[0-9]+$" | sort --version-sort | tail -n 1',
        returnStdout: true
    ).trim()

    sh "git checkout ${LATEST}"
}

/**
    Utility to simplify repeating boilerplate of defining parallel steps
    over array of folders. Creates a map from @names array where each value
    is @action called with each name respectively while being wrapped in
    `dir(name)` statement.

    NB: `action` has to be a function, not a closure, otherwise name argument
    will be captured wrongly
 **/
def mapSteps (names, action) {
    def steps = [:]

    for (int i = 0; i < names.size(); ++i) {
        def name = names[i];
        steps[name] = { action(name) }
    }

    return steps
}

/*******************************************************************************

    Actions

    In Groovy it is not possible to define a "static" nested function and
    defining a closure wrongly captures a context, making it break `parallel`
    in weird ways.

    Because of that, all actions to be used with `mapSteps` are to be define
    here as global functions. Each function should be named in relation to stage
    is used in and take exactly one argument - directory name

*******************************************************************************/

def getSources (name) { dir(name) {
    def pr_repo

    // presence of CHANGE_URL environment variable means this pipeline tests
    // Pull Request and has to checkout PR branch instead of master branch
    // for relevant repository:
    def regex = /https:\/\/github.com\/[^\/]+\/([^\/]+)\/pull\/(\d+)/
    def match = (env.CHANGE_URL =~ regex)
    if (match) {
        pr_repo = match[0][1]
    }
    match = null // need to null match to avoid NotSerializableException

    if (pr_repo == name) {
        cloneUpstream()
    }
    else {
        // Checkout matching branches of other repos, either
        // target branch for PRs or identical branch name.
        def base_branch = env.CHANGE_TARGET ?: env.BRANCH_NAME
        clone("https://github.com/dlang/${name}.git", base_branch)
    }
}}

def test_travis_yaml () {
    def script = 'dub test --compiler=$DC'
    if (fileExists('.travis.yml')) {
        def travis_script = sh(script: 'get_travis_test_script', returnStdout: true).trim()
        if (travis_script)
            script = travis_script
    }
    sh script
}

def testDownstreamProject (name) {
    def repo = name // to fix issues with closure
    node { ws(dir: 'dlang_projects') {
        unstash name: "dlang-build"
        withEnv([
                    // KEY+UID prepends to EnvVars, see http://javadoc.jenkins.io/hudson/EnvVars.html
                    "PATH+BIN=${env.WORKSPACE}/distribution/bin",
                    "LIBRARY_PATH+LIB=${env.WORKSPACE}/distribution/libs",
                    "LD_LIBRARY_PATH+LIB=${env.WORKSPACE}/distribution/libs",
                    'DC=dmd',
                    'DMD=dmd',
                    // set HOME to separate concurrent ~/.dub user paths
                    "HOME=${env.WORKSPACE}"
                ]) {
            dir(repo) {
                if (repo == 'rejectedsoftware/vibe.d')
                    // workaround for core.thread.Fiber.state change
                    clone("https://github.com/${repo}.git", 'v0.7.31-rc.2')
                else
                    cloneLatestTag("https://github.com/${repo}.git")
                switch (repo) {
                case ['Hackerpilot/DCD', 'Hackerpilot/dfix']:
                    sh 'make DMD=$DMD'
                    break;

                case 'gtkd-developers/GtkD':
                    sh 'make DC=$DC'
                    break;

                case 'higgsjs/Higgs':
                    sh 'make -C source test DC=$DC'
                    break;

                case 'rejectedsoftware/vibe.d':
                    // use DC=dmd to workaround https://github.com/dlang/dub/pull/966
                    sh 'DC=dmd VIBED_DRIVER=libevent BUILD_EXAMPLE=1 RUN_TEST=1 ./travis-ci.sh'
                    sh 'DC=dmd VIBED_DRIVER=libasync BUILD_EXAMPLE=0 RUN_TEST=0 ./travis-ci.sh'
                    break;

                case 'dlang/dub':
                    sh '''
                      rm test/issue884-init-defer-file-creation.sh # FIXME

                      jq '.versions["vibe-d"]="0.7.31-rc.2"' < dub.selections.json | sponge dub.selections.json
                      dub fetch ddox --version=0.15.18
                      jq '.versions["vibe-d"]="0.7.31-rc.2"' < $HOME/.dub/packages/ddox-0.15.18/ddox/dub.selections.json | sponge $HOME/.dub/packages/ddox-0.15.18/ddox/dub.selections.json
                    '''
                    sh 'DC=$DC ./travis-ci.sh'
                    break;

                case 'msgpack/msgpack-d':
                    sh 'DMD=$DMD MODEL=64 make -f posix.mak unittest'
                    break;

                case 'economicmodeling/containers':
                    sh 'make -B -C test/'
                    break;

                case 'BlackEdder/ggplotd':
                    // workaround https://github.com/BlackEdder/ggplotd/issues/34
                    sh 'sed -i \'s|auto seed = unpredictableSeed|auto seed = 54321|\' source/ggplotd/example.d'
                    test_travis_yaml()
                    break;

                case 'rejectedsoftware/diet-ng':
                    sh 'sed -i \'s|dependency "vibe-d".*|dependency "vibe-d" version="0.7.31-rc.2"|\' examples/htmlserver/dub.sdl'
                    test_travis_yaml()
                    break;

                default:
                    test_travis_yaml()
                    break;
                }
            }
        }
        sh """
            rm -r '${env.WORKSPACE}/distribution'
            if [ -d '${env.WORKSPACE}/.dub/packages' ]; then
                find '${env.WORKSPACE}/.dub/packages' -type d -name .dub -exec rm -r {} +
            fi
        """
    }}
}


/*******************************************************************************

    Stages

*******************************************************************************/

def runPipeline() {
    /* Use the same workspace, no matter what job (dmd, druntime,...)  triggered
     * the build.  The workspace step will take care of concurrent test-runs and
     * allocate additional workspaces if necessary.  This setup avoids to
     * reclone repos for each test-run.
     */
    node { ws(dir: 'dlang_ci') {
        def projects = [ 'dmd', 'druntime', 'phobos', 'dub', 'tools' ]

        stage ('Clone') {
            parallel mapSteps(projects, this.&getSources)
        }

        stage ('Build Compiler') {
            // main compilation process can't be parallel because each repo
            // expects previous one to be already built and present in parent
            // folder

            def action = { sh "make -f posix.mak AUTO_BOOTSTRAP=1 --jobs=4" }

            dir('dmd',      action)
            dir('dmd/src', { sh "make -f posix.mak AUTO_BOOTSTRAP=1 dmd.conf" })
            dir('druntime', action)
            dir('phobos',   action)
        }

        stage ('Build Tools') {
            def repos = [
                'dub': {
                    withEnv(["PATH=${env.WORKSPACE}/dmd/src:${env.PATH}"]) {
                        dir ('dub') { sh "./build.sh" }
                    }
                },
                'tools': {
                    withEnv(["PATH=${env.WORKSPACE}/dmd/src:${env.PATH}"]) {
                        dir ('tools') { sh "make -f posix.mak RELEASE=1 --jobs=4" }
                    }
                }
            ]

            parallel repos
        }

        stage ("Package distribution") {
            // ideally this step should be in sync with the release tars
            sh '''#!/usr/bin/env bash
            set -ueo pipefail

            rm -rf distribution
            mkdir -p distribution/{bin,imports,libs}
            cp --archive --link dmd/src/dmd dub/bin/dub tools/generated/linux/64/rdmd distribution/bin/
            cp --archive --link phobos/etc phobos/std druntime/import/* distribution/imports/
            cp --archive --link phobos/generated/linux/release/64/libphobos2.{a,so,so*[!o]} distribution/libs/
            echo '[Environment]
DFLAGS=-I%@P%/../imports -L-L%@P%/../libs -L--export-dynamic -L--export-dynamic -fPIC' > distribution/bin/dmd.conf
            '''
            stash name: "dlang-build", includes: "distribution/**"
        }
    }}

    def dub_projects = [
        "Abscissa/libInputVisitor",
        "BlackEdder/ggplotd",
        "DerelictOrg/DerelictFT",
        "DerelictOrg/DerelictGL3",
        "DerelictOrg/DerelictGLFW3",
        "DerelictOrg/DerelictSDL2",
        "DlangScience/scid",
        "Hackerpilot/libdparse",
        "ariovistus/pyd",
        "atilaneves/unit-threaded",
        "d-gamedev-team/gfm",
        "dlang/dub",
        "economicmodeling/containers",
        "higgsjs/Higgs",
        "kyllingstad/zmqd",
        "lgvz/imageformats",
        "msgpack/msgpack-d",
        "msoucy/dproto",
        "nomad-software/dunit",
        "rejectedsoftware/diet-ng",
        "rejectedsoftware/vibe.d",
        "repeatedly/mustache-d",
        "s-ludwig/taggedalgebraic",
    ]

    stage ('Test Projects') {
        parallel mapSteps(dub_projects, this.&testDownstreamProject)
    }
}

return this; // return script
