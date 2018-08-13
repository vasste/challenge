import khttp.get
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

fun main(args: Array<String>) {
    // there is a project define the most committees for a week
    val start = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)
    val end = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
    val requestParameters = mapOf(Pair("Authorization", "Basic c3RlNzAzOm1vaHdvbzZHUEBzcw=="), Pair("Accept", "application/json"))
    //println(readGitCommittedLines("tos", start, end, requestParameters))
    println(readJiraContentModifications("tos", start, end, requestParameters))
}

const val FISH_EYE_URL = "https://tosfish.iteclientsys.local/rest-service-fe"
const val CONFLUENCE_URL = "https://tosconf.iteclientsys.local/rest/api/content"
const val JIRA_URL = "https://tosjira.iteclientsys.local/rest/api/2"
val DATA_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")!!

// select revisions where date in [2008-03-08, 2008-04-08]
// https://confluence.atlassian.com/fisheye/eyeql-reference-guide-298976796.html?_ga=2.197841449.789708941.1533913490-493128539.1533552482
// https://tosfish.iteclientsys.local/rest-service-fe/tos?
// fishEye, https://docs.atlassian.com/fisheye-crucible/3.3.3/wadl/fisheye.html#d2e338
// https://tosfish.iteclientsys.local/rest-service-fe/revisionData-v1/changesetList/tos
// https://tosfish.iteclientsys.local/rest-service-fe/revisionData-v1/changeset/tos/a9e174976d0586555c1d10c375e48490671d025c
// https://tosfish.iteclientsys.local/rest-service-fe/revisionData-v1/revisionInfo/tos/?path=AdminGUI/src/main/java/com/devexperts/tos/ui/admin/riskmonitor/RiskPanel.java&revision=7f8cdb314790e65e82bc6ca1af846b7e259e88bf
fun readGitCommittedLines(project: String, fromTime: Long, toTime: Long, requestParameters: Map<String, String>): Map<String, Int> {
    val from = formatTime(fromTime)
    val to = formatTime(toTime)
    var userLines: Map<String, Int> = mutableMapOf()
    val query = URLEncoder.encode("select revisions where date in [$from, $to) return author, linesAdded, linesRemoved", "utf-8")
    val statistics = get("$FISH_EYE_URL/search-v1/queryAsRows/$project?query=$query", requestParameters).jsonObject
    for (row in statistics["row"] as JSONArray) {
        val item = jA(jO(row)["item"])
        val lines = userLines.getOrDefault(item[0] as String, 0)
        userLines = userLines.plus(Pair(item[0] as String, lines + item[1] as Int + item[2] as Int))
    }
    return userLines
}

// confluence
//  https://docs.atlassian.com/ConfluenceServer/rest/6.10.1/#content-getContentById
fun readConfluenceContentModifications(space: String, fromTime: Long, toTime: Long,
                                       requestParameters: Map<String, String>): Map<String, Int> {
    val from = formatTime(fromTime)
    val to = formatTime(toTime)
    val query = URLEncoder.encode("space=$space and (lastmodified >= $from and lastmodified <= $to " +
            "or created >= $from and created <= $to", "utf-8")
    val statistics = get("$CONFLUENCE_URL/search?cql=$query&expand=version", requestParameters).jsonObject
    var writtenLines: Map<String, Int> = mutableMapOf()
    for (result in jA(statistics["results"])) {
        val updater = jO(jO(jO(result)["version"])["by"])["username"] as String
        writtenLines = writtenLines.plus(Pair(updater, 1 + writtenLines.getOrDefault(updater, 0)))
    }
    return writtenLines
}

// jira
// use JiraQL for all
// https://docs.atlassian.com/software/jira/docs/api/REST/7.6.1/?_ga=2.49922081.602716363.1533552482-493128539.1533552482#api/2/search-search
// https://tosjira.iteclientsys.local/rest/api/2/search
// project = alerts project = alerts and (updated >= 2018-08-01 and  updated <= 2018-08-03 or created >= 2018-08-01 and  created <= 2018-08-03)
fun readJiraContentModifications(project: String, fromTime: Long, toTime: Long,
                                 requestParameters: Map<String, String>): Map<String, Int> {
    val from = formatTime(fromTime)
    val to = formatTime(toTime)
    val query = URLEncoder.encode("project=$project and (updated >= $from and updated <= $to or " +
            "created >= $from and created <= $to)", "utf-8")
    val statistics = get("$JIRA_URL/search?jpl=$query&fields=changelog&expand=changelog", requestParameters).jsonObject
    var modifiedTimes: Map<String, Int> = mutableMapOf()
    for(issue in jA(statistics["issues"])) {
        for(change in jA(jO(jO(issue)["changelog"])["histories"])) {
            val author = jO(jO(change)["author"])["name"] as String
            for(change in jA(jO(change)["items"])) {

            }
            modifiedTimes = modifiedTimes.plus(Pair(author, 1 + modifiedTimes.getOrDefault(author, 0)))
        }
    }
    return modifiedTimes
}

fun jO(any: Any): JSONObject = any as JSONObject
fun jA(any: Any): JSONArray = any as JSONArray
fun formatTime(time: Long) = DATA_FORMATTER.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(time), ZoneOffset.UTC))]