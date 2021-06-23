package no.difi.jenkins.pipeline

import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

import static groovy.json.JsonOutput.toJson
import static java.util.Collections.singletonList
import static java.util.stream.Collectors.joining

Map config
ErrorHandler errorHandler

void setSourceCodeRepository(def repository) {
    try {
        setField("customfield_${config.fields.sourceCodeRepository}", repository)
    } catch (e) {
        errorHandler.trigger "Failed to set source code repository on issue: ${e}"
    }
}

private void setField(def field, def value) {
    Map fields = [:]
    fields.put(field, value)
    try {
        jiraEditIssue idOrKey: issueId(), issue: [fields: fields]
    } catch (e) {
        errorHandler.trigger "Failed to set field ${field} on issue: ${e}"
    }
}

private List<String> issuesWithStatus(def status, def repository) {
    try {
        echo "Searching for issues with status '${statusName status}'..."
        def response = jiraJqlSearch jql: "status = ${status} and cf[${config.fields.sourceCodeRepository}] ~ '${repository}'"
        List<String> issues = response.data.issues['key'] as List<String>
        echo "Following issues have status '${statusName status}': ${issues}"
        return issues
    } catch (e) {
        errorHandler.trigger "Failed to get list of issues with status '${statusName status}': ${e}"
    }
}

/**
 * Issues that should be manually verified are those that belong to the given repository and have a status indicating
 * they are under manual verification or have been manually verified without success but are not closed.
 * These issues will have their status set to <i>manualVerification</i> and the given fix version in order to be
 * manually verified again.
 * @param version
 * @param repository
 */
void updateIssuesForManualVerification(def version, def repository) {
    issuesWithStatus(config.statuses.manualVerification, repository).each { issueWithStatusToChange ->
        fixVersion issueWithStatusToChange, version
    }
    issuesWithStatus(config.statuses.manualVerificationOk, repository).each { issueWithStatusToChange ->
        fixVersion issueWithStatusToChange, version
    }
    issuesWithStatus(config.statuses.manualVerificationFailed, repository).each { issueWithStatusToChange ->
        fixVersion issueWithStatusToChange, version
        if (isTechTask(issueWithStatusToChange)) {
            changeIssueStatus issueWithStatusToChange, config.transitions.closeWithoutTesting
        } else {
            changeIssueStatus issueWithStatusToChange, config.transitions.retryManualVerificationFromFailure
        }
    }
}

String issueId() {
    env.BRANCH_NAME.tokenize('/')[-1]
}

private void newVersion(def version) {
    try {
        jiraNewVersion version: [name: version, project: projectKey()]
    } catch (e) {
        errorHandler.trigger "Failed to create new fix version: ${e.message}"
    }
}

void createAndSetFixVersion(def version) {
    fixVersion version
}

void fixVersion(def version) {
    fixVersion issueId(), version
}

void fixVersion(String issueId, def version) {
    try {
        setField("customfield_${config.fields.buildVersion}", version)
    } catch (e) {
        errorHandler.trigger "Failed to set build version ${version} on issue ${issueId}: ${e.message}"
    }
}

String fixVersions() {
    return fixVersions(issueId())
}


String fixVersions(String issueId) {
    try {
        issueFields(issueId)['customfield_${config.fields.buildVersion}'] as String
    } catch (e) {
        errorHandler.trigger "Failed to get fix version: ${e.message}"
    }
}

Map issueFields(def issueId) {
    try {
        jiraGetIssue(idOrKey: issueId).data.fields
    } catch (e) {
        errorHandler.trigger "Failed to get issue fields: ${e.message}"
    }
}

String issueStatus() {
    issueStatus issueId()
}

String issueStatus(def issueId) {
    try {
        issueStatus issueFields(issueId)
    } catch (e) {
        errorHandler.trigger "Failed to get issue status: ${e.message}"
    }
}

boolean issueStatusIs(def targetStatus) {
    issueStatusIs issueId(), targetStatus
}

boolean issueStatusIs(def issueId, def targetStatus) {
    issueStatus(issueId) == targetStatus as String
}

String issueSummary() {
    try {
        issueSummary issueFields(issueId())
    } catch (e) {
        errorHandler.trigger "Failed to get issue summary: ${e.message}"
    }
}

String projectKey() {
    projectKey issueId()
}

String projectKey(String issueId) {
    try {
        projectKey issueFields(issueId)
    } catch (e) {
        errorHandler.trigger "Failed to get project key: ${e.message}"
    }
}

static String issueStatus(Map issueFields) {
    issueFields['status']['id']
}

String issueType(Map issueFields) {
    issueFields['issuetype']['id']
}

boolean isTechTask() {
    isTechTask issueId()
}

boolean isTechTask(String issueId) {
    // id=20: Story
    // id=13: Technical Task Collection
    // id=11: Defect Collection
    issueType(issueFields(issueId)) == "13"
}

static String issueSummary(Map issueFields) {
    issueFields['summary'] ?: ""
}


static String projectKey(Map issueFields) {
    issueFields['project']['key']
}

static String components(Map issueFields) {
    issueFields['components']
}

void readyForCodeReview() {
    changeIssueStatus config.transitions.readyForCodeReview
}

void startVerification() {
    changeIssueStatus config.transitions.startVerification
}

void startWork() {
    String issueStatus = issueStatus()
    switch (issueStatus) {
        case config.statuses.inProgress as String:
            break
        case config.statuses.open as String:
            changeIssueStatus config.transitions.start
            break
        case config.statuses.readyForVerification as String:
            changeIssueStatus config.transitions.cancelVerification
            break
        case config.statuses.codeReview as String:
            changeIssueStatus config.transitions.resumeWork
            break
        case config.statuses.codeApproved as String:
            changeIssueStatus config.transitions.resumeWorkFromApprovedCode
            break
        default:
            errorHandler.trigger "Can't set issue status to 'in progress'"
            break
    }
    if (!issueStatusIs(config.statuses.inProgress))
        errorHandler.trigger "Issue status is not 'in progress'"
}

void startManualVerification() {
    if (isTechTask()) {
        addComment("Issue deployed successfully to systest, directly closed by Jenkins without testing since tech.task.")
        changeIssueStatus config.transitions.closeWithoutStaging
    } else {
        changeIssueStatus config.transitions.startManualVerification
    }
}

void stagingFailed() {
    changeIssueStatus config.statuses.manualVerification, config.transitions.failManualVerification
    changeIssueStatus config.statuses.codeApproved, config.transitions.failStagingDeploy
    if (isTechTask()) {
        changeIssueStatus config.statuses.manualVerificationFailed, config.transitions.closeWithoutTesting
    }
}

void resumeWork() {
    echo "Changing issue status to 'in progress'"
    String issueStatus = issueStatus()
    switch (issueStatus) {
        case config.statuses.readyForVerification as String:
            config.transitions.cancelVerification
            break
        case config.statuses.codeReview as String:
            changeIssueStatus config.transitions.resumeWork
            break
        case config.statuses.codeApproved as String:
            changeIssueStatus config.transitions.resumeWorkFromApprovedCode
            break
        default:
            echo "Can't set issue status to 'in progress'"
            break
    }
    echo "Issue status changed to 'in progress'"
}

void close(def version, def repository) {
    if (issueStatusIs(config.statuses.codeApproved)) {
        echo "Closing issue"
        changeIssueStatus config.transitions.closeWithoutStaging
    } else {
        List<String> issues = issuesToClose version, repository
        echo "Closing all issues with build version ${version}: ${issues}"
        issues.each { issue ->
            echo "Closing issue ${issue}"
            changeIssueStatus issue, config.transitions.close
        }
    }
}

private List<String> issuesToClose(def version, def repository) {
    try {
        echo 'Searching for issues to close...'
        def response = jiraJqlSearch jql: "status = ${config.statuses.manualVerificationOk} and cf[${config.fields.sourceCodeRepository}] ~ '${repository}' and cf[${config.fields.buildVersion}] ~ '${version}'"
        List<String> issues = response.data.issues['key'] as List<String>
        echo "Following issues should be closed: ${issues}"
        issues
    } catch (e) {
        errorHandler.trigger "Failed to get list of issues that should be closed: ${e}"
    }
}

boolean waitUntilManuellTesterHasFinished() {
    echo "Waiting for issue status to change from 'Deploy to test'..."
    Poll pollResponse = pollUntilIssueStatusIsNot(config.statuses.manualVerification)
    if (pollResponse == null) return false
    waitForCallback(pollResponse)
    // Godkjenner saka dersom trykka proceed-knappen i jenkins
    if (issueStatusIs(config.statuses.manualVerification))
        changeIssueStatus config.transitions.approveManualVerification
    assertIssueStatusIn([config.statuses.manualVerificationOk, config.statuses.manualVerificationFailed])
}

boolean waitUntilVerificationIsStarted() {
    echo "Waiting for issue status to change from 'ready for verification'..."
    Poll pollResponse = pollUntilIssueStatusIsNot(config.statuses.readyForVerification)
    if (pollResponse == null) return false
    waitForCallback(pollResponse)
    if (issueStatusIs(config.statuses.readyForVerification))
        changeIssueStatus config.transitions.startVerification
    assertIssueStatusIn([config.statuses.codeReview, config.statuses.codeApproved])
}

boolean waitUntilCodeReviewIsFinished() {
    echo "Waiting for issue status to change from 'code review'..."
    Poll poll = pollUntilIssueStatusIsNot config.statuses.codeReview
    if (poll == null) return false
    waitForCallback(poll)
    if (issueStatusIs(config.statuses.codeReview))
        changeIssueStatus config.transitions.approveCode
}

boolean failIfCodeNotApproved() {
    if (!isCodeApproved()) {
        return true
    }
}

private boolean isCodeApproved() {
    issueStatusIs config.statuses.codeApproved
}

boolean waitUntilManualVerificationIsStarted() {
    echo "Waiting for issue status to change to 'manual verification'..."
    Poll pollResponse = pollUntilIssueStatusIsNot config.statuses.codeApproved
    if (pollResponse == null) return false
    waitForCallback(pollResponse)
    assertIssueStatusIn([config.statuses.manualVerification, config.statuses.manualVerificationOk, config.statuses.manualVerificationFailed])
}

private boolean assertIssueStatusIn(List statuses) {
    int issueStatus = issueStatus() as Integer
    if (issueStatus in statuses) {
        return true
    } else {
        errorHandler.trigger("Issue status is ${issueStatus}, which is not in ${statuses}")
    }
}

boolean waitUntilManualVerificationIsFinishedAndAssertSuccess(def sourceCodeRepository) {
    List<String> issues = issuesWithStatus config.statuses.manualVerification, sourceCodeRepository
    echo """Waiting for following issues to complete manual verification:
            ${issues.stream().sorted().collect(joining("\n"))}
    """
    return waitUntilManualVerificationIsFinished(issues)
}

private boolean waitUntilManualVerificationIsFinished(List<String> issues) {
    echo "Waiting for issue status to change from 'manual verification' for ${issues}..."
    if (!issues.isEmpty()) {
        Poll pollResponse = pollUntilIssueStatusIsNot issues, config.statuses.manualVerification
        if (pollResponse == null) return false
        waitForCallback(pollResponse)
    }
    List<String> failedIssues = new ArrayList<>()
    List<String> notVerifiedIssues = new ArrayList<>()
    issues.each { issueId ->
        echo "Checking issue status for ${issueId}..."
        if (issueStatus(issueId) == config.statuses.manualVerificationFailed) {
            echo "Issue ${issueId} failed verification -- creating a bug issue"
            createBugForFailedVerification issueId
            failedIssues.add issueId
        } else if (issueStatus(issueId) == config.statuses.manualVerificationFailed) {
            // We are here because Proceed was selected in the input dialog before polling completed
            echo "Issue ${issueId} is not yet verified -- creating a bug issue"
            notVerifiedIssues.add issueId
        }
    }
    if (!failedIssues.isEmpty()) {
        echo "Following issues failed verification: ${failedIssues}"
        return false
    }
    if (!notVerifiedIssues.isEmpty()) {
        echo "Following issues are not verified: ${notVerifiedIssues}"
        return waitUntilManualVerificationIsFinished(notVerifiedIssues)
    }
    echo "All issues were successfully verified: ${issues}"
    return true
}

/**
 * Waits synchronously for a callback for a given poll.
 */
private void waitForCallback(Poll poll) {
    try {
        input message: 'Waiting for callback from polling-agent', id: poll.callbackId()
    } finally {
        deletePollJob poll.pollId()
    }
}

private void deletePollJob(String pollId) {
    try {
        httpRequest(
                url: "http://polling-agent:8080/jiraStatusPolls/${pollId}",
                httpMode: 'DELETE'
        )
    } catch (e) {
        echo "Failed to delete poll job ${pollId}: ${e}"
    }
}

private void createBugForFailedVerification(def issueId) {
    try {
        Map issueFields = issueFields issueId
        Map newFields = [:]
        newFields.put('project', [key: projectKey(issueFields)])
        newFields.put('summary', "Manual verification of ${issueId} failed")
        newFields.put('issuetype', [id: config.issues.bug])
        newFields.put('components', components(issueFields))
        newFields.put('description', "Manual verification failed for issue ${issueId}. Check the build log at ${BUILD_URL}console.")
        def response = jiraNewIssue issue: [fields: newFields]
        jiraLinkIssues type: config.issueLinks.failedVerification, inwardKey: response.data.key, outwardKey: issueId
    } catch (e) {
        echo "Failed to create bug for issue ${issueId} that failed verification: ${e}"
    }
}

private void changeIssueStatus(def transitionId) {
    changeIssueStatus issueId(), transitionId
}

private void changeIssueStatus(String issueId, def transitionId) {
    def status = 'N/A'
    try {
        status = issueStatus issueId
        echo "Transition issue ${issueId} from status '${statusName status}' with transition '${transitionName transitionId}'"
        jiraTransitionIssue idOrKey: issueId, input: [transition: [id: transitionId]]
    } catch (e) {
        errorHandler.trigger "Failed to transition issue ${issueId} from status '${statusName status}' with transition '${transitionName transitionId}': ${e.message}"
    }
}

private String transitionName(def transitionId) {
    switch (transitionId) {
        case config.transitions.start: return 'start'
        case config.transitions.readyForCodeReview: return 'ready for code review'
        case config.transitions.startVerification: return 'start verification'
        case config.transitions.cancelVerification: return 'cancel verification'
        case config.transitions.resumeWork: return 'resume work'
        case config.transitions.approveCode: return 'approve code'
        case config.transitions.resumeWorkFromApprovedCode: return 'resume work from approved code'
        case config.transitions.startManualVerification: return 'start manual verification'
        case config.transitions.approveManualVerification: return 'approve manual verification'
        case config.transitions.failManualVerification: return 'fail manual verification'
        case config.transitions.failStagingDeploy: return 'fail staging deploy'
        case config.transitions.retryManualVerificationFromSuccess: return 'retry manual verification from success'
        case config.transitions.retryManualVerificationFromFailure: return 'retry manual verification from failure'
        case config.transitions.closeWithoutStaging: return 'close without staging'
        case config.transitions.close: return 'close'
        default: return "<transition ${transitionId}>"
    }
}

private String statusName(def statusId) {
    switch (statusId as Integer) {
        case config.statuses.open: return 'open'
        case config.statuses.inProgress: return 'in progress'
        case config.statuses.readyForVerification: return 'ready for verification'
        case config.statuses.codeApproved: return 'code approved'
        case config.statuses.codeReview: return 'code review'
        case config.statuses.manualVerification: return 'manual verification'
        case config.statuses.manualVerificationOk: return 'manual verification ok'
        case config.statuses.manualVerificationFailed: return 'manual verification failed'
        default: return "<status ${statusId}>"
    }
}

private void changeIssueStatus(def sourceStatus, def transitionId) {
    if (issueStatusIs(sourceStatus)) {
        changeIssueStatus transitionId
    }
}

private Poll pollUntilIssueStatusIsNot(def targetStatus) {
    pollUntilIssueStatusIsNot singletonList(issueId()), targetStatus
}

private Poll pollUntilIssueStatusIsNot(List<String> issueIds, def targetStatus) {
    String callbackId = callbackId()
    try {
        String pollId = httpRequest(
                url: pollingAgentUrl(),
                httpMode: 'POST',
                contentType: 'APPLICATION_JSON_UTF8',
                requestBody: toJson(
                        [
                                jiraAddress         : config.url,
                                callbackAddress     : callbackAddress(callbackId),
                                negativeTargetStatus: targetStatus,
                                issues              : issueIds
                        ]
                )
        ).content
        new Poll(pollId: pollId, callbackId: callbackId)
    } catch (e) {
        errorHandler.trigger("Initiating polling failed: ${e}")
    }
}

private String callbackId() {
    UUID.randomUUID().toString().toUpperCase()
    // Upper-casing as a workaround because Jenkins upper-cases first letter of the id
}

private String callbackAddress(String callbackId) {
    "${internalBuildUrl()}input/${callbackId}/proceedEmpty"
}

private String internalBuildUrl() {
    "${BUILD_URL}".replaceFirst("${JENKINS_URL}", 'http://jenkins:8080/')
}

private static String pollingAgentUrl() {
    'http://polling-agent:8080/jiraStatusPolls'
}

void addFailureComment() {
    echo "Adding failure comment to Jira issue"
    if (env.errorMessage != null)
        addComment("[Build ${env.BUILD_NUMBER}|${env.BUILD_URL}] failed in stage '${STAGE_NAME}': ${env.errorMessage}")
    else
        addComment("[Build ${env.BUILD_NUMBER}|${env.BUILD_URL}] failed in stage '${STAGE_NAME}'")
}

void addAbortedComment() {
    echo "Adding aborted comment to Jira issue"
    addComment("[Build ${env.BUILD_NUMBER}|${env.BUILD_URL}] was aborted in '${STAGE_NAME}'")
}

void addComment(String comment) {
    try {
        jiraAddComment(
                idOrKey: issueId(),
                comment: comment,
                auditLog: false
        )
    } catch (e) {
        errorHandler.trigger "Failed to add comment to issue: ${e.message}"
    }
}
