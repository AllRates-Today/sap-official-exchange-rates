/*
 * SAP Integration Suite (Cloud Integration) script step:
 * fetch official exchange-rate tables from the AllRatesToday API.
 *
 * Daily run (LookbackDays = 0): one call to .../latest.
 * Backfill (LookbackDays = N): one call per calendar day from today-N to today on the
 * dated endpoint, which returns the table in force on that day. Weekends and holidays
 * return the previous business day's table; duplicates are dropped by rate_date, so the
 * IDoc carries each published table once. Dated tables need a paid plan.
 *
 * Exchange properties (the package's Content Modifier sets them from the iFlow's
 * externalized parameters):
 *   Source          bank or tax authority code, e.g. ecb, rbi, hmrc, bazg (see /central-bank-rates-api/)
 *   CustomsCountry  ISO country code for customs valuation rates, e.g. NL, US, IN.
 *                   When set, it wins over Source (.../api/v1/customs/{country}/...).
 *   ApiKeyAlias     name of the Secure Parameter holding the API key (default AllRatesToday_API_Key)
 *   LookbackDays    0 (default) = latest table only; 1-366 = backfill window in days
 *   AsOfDate        optional YYYY-MM-DD end of the backfill window (default today, UTC)
 *   BaseUrl         default https://allratestoday.com
 *
 * Output body: {"tables":[table, ...]} in date order, for ExchangeRateToIdoc.groovy or
 * ExchangeRateToS4Cloud.groovy. Output property AllRatesCalls = API calls made.
 */
import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.time.LocalDate
import java.time.ZoneOffset

def Message processData(Message message) {
    // '-' means "not set": the package's Content Modifier cannot hold an empty constant.
    def p = { String k, String d = '' -> def v = (message.getProperty(k) ?: '').toString().trim(); v && v != '-' ? v : d }
    String base = p('BaseUrl', 'https://allratestoday.com').replaceAll('/+$', '')
    String country = p('CustomsCountry').toUpperCase()
    String source = p('Source').toLowerCase()
    if (!country && !source) throw new IllegalArgumentException('AllRatesToday: set Source (e.g. ecb) or CustomsCountry (e.g. NL)')
    if (country && !(country ==~ /[A-Z]{2}/)) throw new IllegalArgumentException("AllRatesToday: CustomsCountry must be a 2-letter ISO code, got '$country'")
    if (!country && !(source ==~ /[a-z0-9_-]{2,20}/)) throw new IllegalArgumentException("AllRatesToday: invalid Source '$source'")
    String root = country ? "$base/api/v1/customs/$country" : "$base/api/v1/central-bank/$source"

    int lookback = (p('LookbackDays', '0') ?: '0') as int
    if (lookback < 0 || lookback > 366) throw new IllegalArgumentException('AllRatesToday: LookbackDays must be 0-366')
    LocalDate asOf = p('AsOfDate') ? LocalDate.parse(p('AsOfDate')) : LocalDate.now(ZoneOffset.UTC)

    String key = apiKey(p('ApiKeyAlias', 'AllRatesToday_API_Key'))
    def get = binding.hasVariable('httpGet') ? binding.getVariable('httpGet') : this.&httpGet

    def urls = lookback == 0 ? ["$root/latest"] :
        (lookback..0).collect { "$root/${asOf.minusDays(it)}" }
    def byDate = new LinkedHashMap<String, Object>()
    int calls = 0
    for (u in urls) {
        def res = get(u.toString(), key)
        calls++
        if (res.status == 404) continue                        // nothing published on or before that day
        if (res.status != 200) throw new IllegalStateException("AllRatesToday: HTTP ${res.status} from $u: ${res.body?.take(300)}")
        def t = new JsonSlurper().parseText(res.body as String)
        if (t.rate_date && !byDate.containsKey(t.rate_date)) byDate[t.rate_date as String] = t
    }
    if (!byDate) throw new IllegalStateException("AllRatesToday: no table returned from $root")
    def tables = byDate.values().sort { it.rate_date }
    message.setBody(JsonOutput.toJson([tables: tables]))
    message.setProperty('AllRatesCalls', calls)
    message.setHeader('AllRatesTables', tables.size())
    return message
}

/** Secure Parameter (Monitor > Security Material) -> API key. */
String apiKey(String alias) {
    if (binding.hasVariable('apiKeyOverride')) return binding.getVariable('apiKeyOverride')
    def svc = com.sap.it.api.ITApiFactory.getService(com.sap.it.api.securestore.SecureStoreService, null)
    def cred = svc?.getUserCredential(alias)
    if (!cred) throw new IllegalStateException("AllRatesToday: Secure Parameter '$alias' not found. Deploy it under Monitor > Security Material.")
    new String(cred.getPassword() as char[])
}

/** GET with up to two retries on 429 / 5xx. Returns [status, body]. */
Map httpGet(String url, String key) {
    Map res = null
    for (attempt in 1..3) {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection()
        c.setRequestProperty('Authorization', "Bearer $key")
        c.setRequestProperty('Accept', 'application/json')
        c.setRequestProperty('User-Agent', 'AllRatesToday-SAP-Integration-Suite/1.1')
        c.connectTimeout = 15000
        c.readTimeout = 30000
        int status = c.responseCode
        def stream = status < 400 ? c.inputStream : c.errorStream
        res = [status: status, body: stream?.getText('UTF-8') ?: '']
        if (status != 429 && status < 500) return res
        Thread.sleep(attempt * 5000L)
    }
    res
}
