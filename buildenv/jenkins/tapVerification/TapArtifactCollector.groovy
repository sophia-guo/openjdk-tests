#!groovy
/*
 * TapArtifactCollector.groovy
 *
 * Two-stage pipeline that:
 *
 * Stage 1 – Collect TAP artifacts from Jenkins
 * -----------------------------------------------
 * Scans all builds of TEST_PIPELINE_JOB (default: AQA_Test_Pipeline_RELEASE)
 * and finds every build that was triggered (directly or indirectly) by one of
 * the specified upstream builds.  A match is detected in two ways:
 *   a) The build's cause chain contains an upstream project whose name ends
 *      with PIPELINE_NAME and whose build number is in BUILD_NUMBERS.
 *   b) The build's display name description ends with a platform token that
 *      also matches  "<PIPELINE_NAME> ... #<N>" — useful when cause info is
 *      not set but the display name follows the convention:
 *        "#346 - jdk8 : jdk8u504-b01_adopt_RELEASE : x86-64_windows"
 *
 * For every matched build the entire artifact tree is zipped and archived as
 * Jenkins build artifacts of THIS job, named after the platform suffix of the
 * display name (e.g. "x86-64_windows.zip").
 *
 * Stage 2 – Collect TAP attachments from GitHub
 * -----------------------------------------------
 * Given a GitHub issue URL (GITHUB_ISSUE_URL), downloads every attachment
 * whose URL ends in ".tap.txt" from:
 *   • The issue body itself
 *   • All comments on the issue
 *   • Any child issues whose numbers are listed in the issue description in
 *     the form "- #<N>" or "- https://github.com/.../issues/<N>"
 *
 * All downloaded files are archived as Jenkins build artifacts.
 *
 * Parameters
 * ----------
 *   PIPELINE_NAME        Upstream pipeline name to match in cause/description.
 *                        Example: release-openjdk8-pipeline
 *   BUILD_NUMBERS        Comma-separated upstream build numbers.
 *                        Example: 130,131
 *   TEST_PIPELINE_JOB    Jenkins job to scan.  Default: AQA_Test_Pipeline_RELEASE
 *   GITHUB_ISSUE_URL     GitHub issue URL.
 *                        Example: https://github.com/adoptium/aqa-tests/issues/7612
 *   GITHUB_CREDENTIAL    Jenkins credential ID for the GitHub token.
 */

pipeline {
    agent { label 'ci.role.test&&hw.arch.x86&&sw.os.linux' }

    parameters {
        string(
            name: 'PIPELINE_NAME',
            defaultValue: '',
            description: 'Upstream pipeline name to match (e.g. release-openjdk8-pipeline)'
        )
        string(
            name: 'BUILD_NUMBERS',
            defaultValue: '',
            description: 'Comma-separated upstream build numbers to match (e.g. 130,131)'
        )
        string(
            name: 'TEST_PIPELINE_JOB',
            defaultValue: 'AQA_Test_Pipeline_RELEASE',
            description: 'Jenkins job to scan for matching builds'
        )
        string(
            name: 'GITHUB_ISSUE_URL',
            defaultValue: '',
            description: 'GitHub issue URL (e.g. https://github.com/adoptium/aqa-tests/issues/7612)'
        )
        credentials(
            name: 'JENKINS_CREDENTIAL',
            defaultValue: 'jenkins-bot-token',
            description: 'Username with password credential for internal Jenkins REST API calls (user + API token)',
            credentialType: 'com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl',
            required: true
        )
        credentials(
            name: 'GITHUB_CREDENTIAL',
            defaultValue: 'github-bot-token',
            description: 'Secret text credential containing the GitHub personal access token',
            credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl',
            required: false
        )
    }

    options {
        timestamps()
        skipDefaultCheckout()
    }

    stages {

        // -----------------------------------------------------------------------
        // Stage 1: Collect TAP artifacts from matching Jenkins builds
        // -----------------------------------------------------------------------
        stage('Collect Jenkins Artifacts') {
            steps {
                script {
                    def pipelineName  = params.PIPELINE_NAME?.trim()
                    def buildNumbers  = params.BUILD_NUMBERS?.trim()
                    def testJob       = params.TEST_PIPELINE_JOB?.trim() ?: 'AQA_Test_Pipeline_RELEASE'
                    def jenkinsCred   = params.JENKINS_CREDENTIAL?.trim() ?: 'jenkins-bot-token'

                    if (!pipelineName) { error "PIPELINE_NAME parameter must be set." }
                    if (!buildNumbers) { error "BUILD_NUMBERS parameter must be set." }

                    def targetBuildNums = buildNumbers.split(/\s*,\s*/).collect { it.trim() }.findAll { it } as Set

                    echo "=== Stage 1: scanning '${testJob}' for builds triggered by '${pipelineName}' #${targetBuildNums} ==="

                    def jobPath = testJob.split('/').join('/job/')
                    // Fetch the list of all build numbers for the job.
                    def allBuildsJson = fetchJson(
                        "${env.JENKINS_URL}job/${jobPath}/api/json?tree=builds%5Bnumber%5D",
                        "build list of '${testJob}'"
                    )
                    if (!allBuildsJson || !allBuildsJson.builds) {
                        echo "No builds found for '${testJob}'. Skipping Stage 1."
                        return
                    }

                    // Each entry: [number: N, platform: 'x86-64_windows']
                    // One upstream build number fans out to many AQA_Test_Pipeline_RELEASE
                    // builds, one per platform. Each zip is named <platform>.zip.
                    def matchedBuilds = []

                    withCredentials([usernameColonPassword(credentialsId: jenkinsCred, variable: 'JENKINS_AUTH')]) {
                    allBuildsJson.builds.each { b ->
                        def num = b.number as int
                        def buildApiUrl = "${env.JENKINS_URL}job/${jobPath}/${num}/api/json" +
                            "?tree=number,displayName,description,actions%5Bcauses%5BupstreamProject,upstreamBuild,shortDescription%5D%5D"
                        // AQA_Test_Pipeline_RELEASE is public — no auth needed here.
                        def info = fetchJson(buildApiUrl, "'${testJob}' #${num}")
                        if (!info) return

                        // Gate: cause chain must match PIPELINE_NAME + one of BUILD_NUMBERS.
                        // Auth is only needed when fetching the intermediate build-scripts parent build.
                        if (!isBuildTriggeredBy(info, pipelineName, targetBuildNums, 3, env.JENKINS_AUTH)) return

                        // Platform always comes from the build's own display name / description,
                        // since each build is for exactly one platform regardless of which
                        // upstream build number triggered it.
                        def platform = extractPlatformFromDisplayName(
                            info.displayName?.trim() ?: '',
                            info.description?.trim()  ?: ''
                        )
                        echo "  Matched build #${num} — platform: '${platform}'"
                        matchedBuilds << [number: num, platform: platform]
                    }
                    } // end withCredentials

                    if (matchedBuilds.isEmpty()) {
                        echo "No matching builds found in '${testJob}'."
                        return
                    }

                    echo "=== Found ${matchedBuilds.size()} matching build(s) ==="
                    matchedBuilds.each { entry ->
                        def buildUrl = "${env.JENKINS_URL}job/${jobPath}/${entry.number}/"
                        echo "  #${entry.number} — platform: '${entry.platform}' — ${buildUrl}"
                    }
                    echo "Downloading artifacts..."

                    matchedBuilds.each { entry ->
                        def bNum     = entry.number
                        def platform = entry.platform ?: "unknown"
                        def zipName  = "${platform}.zip"
                        echo "  Downloading artifacts from '${testJob}' #${bNum} → ${zipName} ..."
                        try {
                            def artifactZipUrl = "${env.JENKINS_URL}job/${jobPath}/${bNum}/artifact/*zip*/archive.zip"
                            sh "curl -sf -o '${zipName}' '${artifactZipUrl}'"
                        } catch (Exception e) {
                            echo "  WARNING: Failed to download artifacts for build #${bNum}: ${e.message}"
                        }
                    }

                    archiveArtifacts artifacts: '*.zip', allowEmptyArchive: true
                }
            }
        }

        // -----------------------------------------------------------------------
        // Stage 2: Collect .tap.txt attachments from GitHub issue + child issues
        // -----------------------------------------------------------------------
        stage('Collect GitHub TAP Attachments') {
            steps {
                script {
                    def issueUrl    = params.GITHUB_ISSUE_URL?.trim()
                    def credId      = params.GITHUB_CREDENTIAL?.trim() ?: 'github-bot-token'
                    def tapsDir     = 'TAPs'

                    if (!issueUrl) {
                        echo "GITHUB_ISSUE_URL not set — skipping Stage 2."
                        return
                    }

                    // Parse  https://github.com/<owner>/<repo>/issues/<number>
                    def m = issueUrl =~ /github\.com\/([^\/]+\/[^\/]+)\/issues\/(\d+)/
                    if (!m) { error "Cannot parse GITHUB_ISSUE_URL: '${issueUrl}'" }
                    def repoSlug    = m[0][1]
                    def issueNumber = m[0][2]

                    sh "mkdir -p ${tapsDir}"

                    echo "=== Stage 2: collecting .tap.txt attachments for ${repoSlug}#${issueNumber} ==="

                    withCredentials([string(credentialsId: credId, variable: 'GITHUB_TOKEN')]) {

                        // Helper: fetch JSON from GitHub API
                        def ghFetch = { url, label ->
                            def outFile = "gh_${label}.json"
                            sh """curl -sf -H "Authorization: token \${GITHUB_TOKEN}" \
                                -H "Accept: application/vnd.github.v3+json" \
                                "${url}" -o "${outFile}" """
                            return readJSON(file: outFile)
                        }

                        // Helper: download all .tap.txt attachments from a Markdown body.
                        // Mirrors the logic in TapCollection.groovy: only processes lines
                        // that are Markdown attachment links ending with ')' and contain
                        // a github.com files URL. Filters to .tap.txt files only.
                        def downloadTapTxt = { body ->
                            if (!body) return
                            body.split(/\r?\n/).each { line ->
                                if (!line.endsWith(')')) return
                                if (!line.contains('https://github.com/user-attachments/files/') &&
                                    !line.contains("https://github.com/${repoSlug}/files/")) return
                                // Extract URL from Markdown [name](url) — take whichever domain matches
                                def urlStart = line.indexOf('(https://github.com/user-attachments/files/')
                                if (urlStart < 0) urlStart = line.indexOf("(https://github.com/${repoSlug}/files/")
                                if (urlStart < 0) return
                                def url      = line.substring(urlStart + 1, line.lastIndexOf(')'))
                                if (!url.endsWith('.tap.txt')) return
                                def filename = url.split('/').last()
                                echo "    Downloading ${filename} ..."
                                sh "curl -Lsf -o '${tapsDir}/${filename}' '${url}'"
                            }
                        }

                        // --- Main issue ---
                        def apiBase  = "https://api.github.com/repos/${repoSlug}"
                        def issue    = ghFetch("${apiBase}/issues/${issueNumber}", "main_issue")
                        def comments = ghFetch("${apiBase}/issues/${issueNumber}/comments", "main_comments")

                        downloadTapTxt(issue.body)
                        comments.each { c -> downloadTapTxt(c.body) }

                        // --- Child issues linked in main issue description ---
                        def childNums = extractChildIssueNumbers(issue.body ?: '', repoSlug)
                        echo "Found ${childNums.size()} child issue(s): ${childNums}"

                        childNums.eachWithIndex { childNum, idx ->
                            echo "  Processing child issue #${childNum} ..."
                            try {
                                def childIssue    = ghFetch("${apiBase}/issues/${childNum}", "child_${idx}_issue")
                                def childComments = ghFetch("${apiBase}/issues/${childNum}/comments", "child_${idx}_comments")
                                downloadTapTxt(childIssue.body)
                                childComments.each { c -> downloadTapTxt(c.body) }
                            } catch (Exception e) {
                                echo "  WARNING: Failed to fetch child issue #${childNum}: ${e.message}"
                            }
                        }
                    }

                    archiveArtifacts artifacts: "${tapsDir}/**", allowEmptyArchive: true
                }
            }
        }
    }

    post {
        always {
            cleanWs()
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Fetch a URL with curl and return a parsed JSON map.
 * @param auth  Optional "user:token" string for Basic auth (-u flag).  Pass null to skip.
 * Returns null on failure.
 */
def fetchJson(String url, String label, String auth = null) {
    try {
        def authFlag = auth ? "-u '${auth}'" : ''
        def json = sh(script: "curl -sf --connect-timeout 10 ${authFlag} '${url}'", returnStdout: true).trim()
        if (!json) { echo "Empty response for ${label}"; return null }
        return readJSON(text: json)
    } catch (Exception e) {
        echo "Failed to fetch ${label}: ${e.message}"
        return null
    }
}

/**
 * Check whether a build was triggered (directly or indirectly) by the given
 * upstream pipeline name + one of the target build numbers.
 *
 * AQA_Test_Pipeline_RELEASE is not triggered directly by release-openjdk*-pipeline;
 * there is at least one intermediate build-scripts job in between.  This function
 * therefore walks the cause chain upward (up to maxDepth levels) by fetching each
 * intermediate upstream build via the REST API until it either finds a match or
 * exhausts the chain.
 *
 * At each level two match forms are checked:
 *   • Structured: upstreamProject contains pipelineName AND upstreamBuild is in targetBuildNums.
 *   • Text fallback (shortDescription): "Started by upstream project … build number 130"
 *
 * @param auth  "user:token" passed to curl -u for authenticated fetches of internal jobs.
 */
def isBuildTriggeredBy(def buildInfo, String pipelineName, Set targetBuildNums, int maxDepth = 3, String auth = null) {
    def current = buildInfo
    for (int depth = 0; depth < maxDepth; depth++) {
        def actions = current?.actions ?: []
        def allCauses = []
        actions.each { action -> allCauses.addAll(action?.causes ?: []) }

        if (allCauses.isEmpty()) break

        for (cause in allCauses) {
            def proj      = cause?.upstreamProject?.toString() ?: ''
            def upNumStr  = cause?.upstreamBuild?.toString()   ?: ''
            def shortDesc = cause?.shortDescription?.toString() ?: ''

            // Direct structured match
            if (proj.contains(pipelineName) && targetBuildNums.contains(upNumStr)) {
                return true
            }
            // Text fallback in shortDescription
            if (shortDesc.contains(pipelineName)) {
                for (n in targetBuildNums) {
                    if (shortDesc.contains("build number ${n}") || shortDesc.contains("#${n}")) {
                        return true
                    }
                }
            }
        }

        // No match at this level — climb one level up via the first structured upstream cause.
        def parentCause = allCauses.find { it?.upstreamProject && it?.upstreamBuild != null }
        if (!parentCause) break

        def parentProj     = parentCause.upstreamProject.toString()
        def parentBuildNum = parentCause.upstreamBuild.toString()
        def parentJobPath  = parentProj.split('/').join('/job/')
        def parentApiUrl   = "${env.JENKINS_URL}job/${parentJobPath}/${parentBuildNum}/api/json" +
            "?tree=actions%5Bcauses%5BupstreamProject,upstreamBuild,shortDescription%5D%5D"
        def parentInfo = fetchJson(parentApiUrl, "'${parentProj}' #${parentBuildNum}", auth)
        if (!parentInfo) break
        current = parentInfo
    }
    return false
}

/**
 * Extract the platform name from a Jenkins build's display name and/or description.
 *
 * Display name convention:
 *   "#346 - jdk8 : jdk8u504-b01_adopt_RELEASE : x86-64_windows"
 *   → last colon-separated token trimmed → "x86-64_windows"
 *
 * Description convention (HTML, last segment after final ' : ' or last colon):
 *   Same format, so same rule applies.
 *
 * Falls back to the last two underscore-delimited tokens from the description
 * if neither source has a colon (e.g. "Test_openjdk8_hs_special.functional_arm_linux"
 * → "arm_linux").
 *
 * @param displayName  value of buildInfo.displayName
 * @param description  value of buildInfo.description (may contain HTML)
 * @return platform string, or empty string if not determinable
 */
def extractPlatformFromDisplayName(String displayName, String description) {
    // Try display name first (most reliable — always set by Jenkins)
    if (displayName) {
        def parts = displayName.split(':')
        if (parts.size() >= 2) {
            return parts.last().trim()
        }
    }
    // Try description (strip HTML tags first)
    if (description) {
        def plain = description.replaceAll(/<[^>]+>/, '').trim()
        def parts = plain.split(':')
        if (parts.size() >= 2) {
            return parts.last().trim()
        }
        // Last two underscore tokens as final fallback
        def tokens = plain.tokenize('_')
        if (tokens.size() >= 2) {
            return "${tokens[-2]}_${tokens[-1]}"
        }
    }
    return ''
}

/**
 * Parse child issue numbers from a GitHub issue body.
 *
 * Recognises lines containing:
 *   - #<N>
 *   - https://github.com/<owner>/<repo>/issues/<N>
 *
 * Returns a de-duplicated list of issue number strings.
 */
def extractChildIssueNumbers(String body, String repoSlug) {
    def nums = [] as LinkedHashSet
    if (!body) return nums as List

    body.split(/\r?\n/).each { line ->
        // Full URL form
        def urlM = (line =~ /github\.com\/${repoSlug}\/issues\/(\d+)/)
        urlM.each { nums << it[1] }
        // Short form: "- #123" or "closes #123" or "#123"
        def shortM = (line =~ /(?:^|\s|-)#(\d+)/)
        shortM.each { nums << it[1] }
    }
    return nums as List
}
