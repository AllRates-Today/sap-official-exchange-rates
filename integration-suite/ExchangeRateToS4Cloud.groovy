/*
 * SAP Integration Suite (Cloud Integration) script steps for SAP S/4HANA Cloud Public Edition.
 * Input: the output of FetchRates.groovy ({"tables":[...]}) or a single API table.
 *
 * Route A, direct API (no extra SAP subscription):
 *   OData V4 "Currency Exchange Rate" API, communication scenario SAP_COM_0B18,
 *   /sap/opu/odata4/sap/api_currencyexchangerate/srvd_a2x/sap/currencyexchangerate/0001/
 *   processData  -> builds a multipart/mixed $batch body, one POST per rate, each in its own
 *                   change set so one rejected rate does not roll back the others.
 *   checkBatch   -> reads the $batch response: 201 = created, "already exists" = skipped
 *                   (rerun of the same date), anything else fails the message.
 *
 * Route B, SAP Market Rates Management, Bring Your Own Rates (BYOR):
 *   toByor       -> JSON array for the BYOR upload API (marketDataSource BYOR, category 01).
 *                   S/4HANA then pulls the rates with SAP_COM_0203 and the
 *                   "Request Current Market Data" job.
 *
 * Exchange properties (all optional, '-' = not set):
 *   RateType, HomeCurrency, Currencies, Factors, TableRateType   as in ExchangeRateToIdoc.groovy
 *   ByorProvider   BYOR provider code (default Y001)
 *   ByorProperty   BYOR market data property (default CMID)
 *   ByorMaxRecords records allowed per upload request (default 1500; free plan 100)
 *
 * The row logic (direction, ratios, two-sided tables, DEC 9,5 limit) is the same as
 * ExchangeRateToIdoc.groovy; test/cloud.groovy checks both give the same rows.
 */
import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

static Closure props(Message message) {
    // '-' means "not set": the package's Content Modifier cannot hold an empty constant.
    return { String k, String d = '' -> def v = (message.getProperty(k) ?: '').toString().trim(); v && v != '-' ? v : d }
}

static List<Map> rowsFrom(Message message) {
    def p = props(message)
    def body = new JsonSlurper().parseText(message.getBody(String) as String)
    def tables = body.tables != null ? body.tables : [body]
    def opt = [
        rateType: p('RateType', 'M'),
        home    : p('HomeCurrency').toUpperCase(),
        keep    : p('Currencies').toUpperCase().split(',').findAll { it } as Set,
        factors : parseFactors(p('Factors')),
        side    : p('TableRateType').toLowerCase(),
    ]
    def rows = tables.collectMany { buildRows(it, opt) }
    if (!rows) {
        def last = tables ? tables.max { it.rate_date } : [:]
        throw new IllegalStateException("AllRatesToday: no rates to send for ${last?.bank ?: '?'} ${last?.rate_date ?: ''}")
    }
    message.setHeader('AllRatesRows', rows.size())
    rows
}

/** Route A: OData V4 $batch body for API_CURRENCYEXCHANGERATE. */
def Message processData(Message message) {
    def rows = rowsFrom(message)
    String boundary = 'batch_allratestoday'
    def sb = new StringBuilder()
    rows.eachWithIndex { r, i ->
        String cs = "changeset_${i + 1}"
        def entity = [
            ExchangeRateType           : r.RATE_TYPE,
            SourceCurrency             : r.FROM_CURR,
            TargetCurrency             : r.TO_CURRNCY,
            ExchangeRateEffectiveDate  : r.VALID_FROM_ISO,
            AbsoluteExchangeRate       : new BigDecimal(r.EXCH_RATE),
            NumberOfSourceCurrencyUnits: new BigDecimal(r.FROM_FACTOR),
            NumberOfTargetCurrencyUnits: new BigDecimal(r.TO_FACTOR),
        ]
        sb << "--$boundary\r\nContent-Type: multipart/mixed; boundary=$cs\r\n\r\n"
        sb << "--$cs\r\nContent-Type: application/http\r\nContent-Transfer-Encoding: binary\r\nContent-ID: ${i + 1}\r\n\r\n"
        sb << "POST CurrencyExchangeRate HTTP/1.1\r\nContent-Type: application/json\r\nAccept: application/json\r\n\r\n"
        sb << JsonOutput.toJson(entity) << "\r\n--$cs--\r\n"
    }
    sb << "--$boundary--\r\n"
    message.setProperty('AllRatesBatchBody', sb.toString())
    message.setProperty('AllRatesBatchContentType', "multipart/mixed; boundary=$boundary")
    message.setProperty('AllRatesBatchRows', JsonOutput.toJson(rows.collect { [it.FROM_CURR, it.TO_CURRNCY, it.VALID_FROM_ISO].join(' ') }))
    // The first call (GET on the service root) fetches the CSRF token for the session.
    message.setHeader('x-csrf-token', 'Fetch')
    message.setBody('')
    return message
}

/** Between the token GET and the $batch POST: restore the batch body. */
def Message prepareBatch(Message message) {
    message.setBody(message.getProperty('AllRatesBatchBody'))
    message.setHeader('Content-Type', message.getProperty('AllRatesBatchContentType'))
    message.setHeader('Accept', 'multipart/mixed')
    return message
}

/** After the $batch POST: count results, fail on real errors. */
def Message checkBatch(Message message) {
    def res = parseBatchResponse(message.getBody(String) as String)
    def labels = new JsonSlurper().parseText((message.getProperty('AllRatesBatchRows') ?: '[]') as String)
    int created = 0, skipped = 0
    def errors = []
    res.eachWithIndex { r, i ->
        String label = i < labels.size() ? labels[i] : "#${i + 1}"
        if (r.status in [200, 201, 204]) created++
        else if (r.status in [400, 409] && r.body =~ /(?i)already exist/) skipped++
        else errors << "$label: HTTP ${r.status} ${r.body?.take(200)}"
    }
    message.setHeader('AllRatesCreated', created)
    message.setHeader('AllRatesSkipped', skipped)
    if (errors) throw new IllegalStateException("AllRatesToday: ${errors.size()} rate(s) rejected by S/4HANA Cloud:\n" + errors.join('\n'))
    if (!res) throw new IllegalStateException('AllRatesToday: empty $batch response from S/4HANA Cloud')
    message.setBody("created=$created skipped=$skipped")
    return message
}

/** Route B: BYOR upload payload. */
def Message toByor(Message message) {
    def p = props(message)
    def rows = rowsFrom(message)
    int max = (p('ByorMaxRecords', '1500')) as int
    if (rows.size() > max) throw new IllegalStateException("AllRatesToday: ${rows.size()} records exceed the BYOR limit of $max per request. Shorten Lookback_Days or narrow Currencies.")
    def out = rows.collect { r ->
        [providerCode      : p('ByorProvider', 'Y001'),
         marketDataSource  : 'BYOR',
         marketDataCategory: '01',
         key1              : r.FROM_CURR,
         key2              : r.TO_CURRNCY,
         marketDataProperty: p('ByorProperty', 'CMID'),
         effectiveDate     : r.VALID_FROM_ISO,
         effectiveTime     : '00:00:00',
         marketDataValue   : new BigDecimal(r.EXCH_RATE),
         securityCurrency  : null,
         fromFactor        : r.FROM_FACTOR == '1' ? null : new BigDecimal(r.FROM_FACTOR),
         toFactor          : r.TO_FACTOR == '1' ? null : new BigDecimal(r.TO_FACTOR),
         priceQuotation    : null,
         additionalKey     : null]
    }
    message.setBody(JsonOutput.toJson(out))
    message.setHeader('Content-Type', 'application/json')
    return message
}

/** multipart/mixed $batch response -> [[status, body]] in request order. */
static List<Map> parseBatchResponse(String text) {
    def out = []
    // Split on boundary lines; every part that holds an HTTP status line is one response.
    for (part in text.split(/\r?\n(?=--)|^(?=--)/)) {
        def st = part =~ /(?m)^HTTP\/1\.1 (\d{3})/
        if (!st.find()) continue
        String after = part.substring(st.end())
        int blank = after.indexOf('\r\n\r\n') >= 0 ? after.indexOf('\r\n\r\n') + 4 : (after.indexOf('\n\n') >= 0 ? after.indexOf('\n\n') + 2 : after.length())
        out << [status: st.group(1) as int, body: after.substring(Math.min(blank, after.length())).trim()]
    }
    out
}

static Map<String, BigDecimal> parseFactors(String s) {
    s.split(',').findAll { it.contains(':') }.collectEntries {
        def (c, f) = it.split(':')*.trim(); [(c.toUpperCase()): new BigDecimal(f)]
    }
}

static int typeRank(t) {
    int i = ['reference', 'mid', 'import', 'buy', 'sell', 'export'].indexOf(t as String)
    i < 0 ? 99 : i
}

/** Same rules as ExchangeRateToIdoc.buildRows, plus VALID_FROM_ISO. */
static List<Map> buildRows(body, Map opt) {
    String iso = body.rate_date as String
    def out = []
    def seen = [] as Set
    def rates = (body.rates ?: []).findAll { !opt.side || (it.type as String) == opt.side }
    for (r in rates.sort { a, b -> typeRank(a.type) <=> typeRank(b.type) }) {
        String pairKey = [r.base, r.quote].sort().join('/')
        if (!seen.add(pairKey)) continue
        String from = r.base, to = r.quote
        BigDecimal v = new BigDecimal(r.value.toString())
        if (opt.home) {
            if (to == opt.home) { /* already foreign -> home */ }
            else if (from == opt.home) { (from, to) = [to, from]; v = BigDecimal.ONE.divide(v, 12, java.math.RoundingMode.HALF_UP) }
            else continue
        }
        String foreign = from == opt.home ? to : from
        if (opt.keep && !opt.keep.contains(foreign)) continue
        BigDecimal factor = opt.factors[foreign] ?: BigDecimal.ONE
        BigDecimal rate = (v * factor).setScale(5, java.math.RoundingMode.HALF_UP)
        if (rate.signum() <= 0 || rate.precision() - rate.scale() > 4) continue
        out << [RATE_TYPE: opt.rateType, FROM_CURR: from, TO_CURRNCY: to, VALID_FROM: iso.replace('-', ''), VALID_FROM_ISO: iso,
                EXCH_RATE: rate.stripTrailingZeros().toPlainString(),
                FROM_FACTOR: (from == foreign ? factor : BigDecimal.ONE).toPlainString(),
                TO_FACTOR: (to == foreign ? factor : BigDecimal.ONE).toPlainString()]
    }
    out
}
