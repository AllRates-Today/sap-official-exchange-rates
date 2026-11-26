/*
 * SAP Integration Suite (Cloud Integration) script step:
 * AllRatesToday table response (JSON) -> EXCHANGE_RATE01 IDoc XML (Text/XML content type)
 * for the IDoc receiver adapter. Field names and lengths follow SAP's EXCHANGE_RATE01
 * WSDL (segments E1EXCHANGE_RATE / E1BP1093_0) as used in SAP's own exchange-rate
 * integration recipe on github.com/SAP/apibusinesshub-integration-recipes.
 *
 * Input body: GET https://allratestoday.com/api/v1/customs/{country}/latest
 *         or  GET https://allratestoday.com/api/v1/central-bank/{source}/latest
 *         or  the output of FetchRates.groovy: {"tables":[table, table, ...]}, one
 *             table per published date (a backfill). All rows go into one IDoc,
 *             each with its own VALID_FROM.
 *
 * Exchange properties (set them in a Content Modifier, all optional except partners):
 *   RateType           TCURR rate type, e.g. ZCUS (default M)
 *   HomeCurrency       write every rate as foreign -> home (direct quotation), e.g. EUR.
 *                      Default: the response's own direction, unchanged.
 *   Currencies         comma list to keep, e.g. USD,GBP,JPY (default: all)
 *   Factors            ratios matching OBBS/TCURF, e.g. JPY:100,KRW:1000 (default 1)
 *   SenderPort, SenderPartner, ReceiverPort, ReceiverPartner   EDI_DC40 partner data (LS)
 *   UpdateAllowed      X (default) / blank       -> UPD_ALLOW
 *   ChangeFixed        X / blank (default blank) -> CHG_FIXED
 *   AllowedDeviation   000-999 percent (default 000) -> DEV_ALLOW
 *
 * Output headers: AllRatesRateDate (the latest table date), AllRatesSource, AllRatesRows.
 */
import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonSlurper
import groovy.xml.MarkupBuilder

def Message processData(Message message) {
    // '-' means "not set": the package's Content Modifier cannot hold an empty constant.
    def p = { String k, String d = '' -> def v = (message.getProperty(k) ?: '').toString().trim(); v && v != '-' ? v : d }
    def body = new JsonSlurper().parseText(message.getBody(String) as String)
    def tables = body.tables != null ? body.tables : [body]
    def opt = [
        rateType : p('RateType', 'M'),
        home     : p('HomeCurrency').toUpperCase(),
        keep     : p('Currencies').toUpperCase().split(',').findAll { it } as Set,
        factors  : parseFactors(p('Factors')),
        side     : p('TableRateType').toLowerCase(),
    ]
    def rows = tables.collectMany { buildRows(it, opt) }
    def last = tables ? tables.max { it.rate_date } : [:]
    if (!rows) throw new IllegalStateException("AllRatesToday: no rates to send for ${last?.bank ?: '?'} ${last?.rate_date ?: ''}")
    message.setBody(toIdocXml(rows, [
        sndpor: p('SenderPort'), sndprn: p('SenderPartner'),
        rcvpor: p('ReceiverPort'), rcvprn: p('ReceiverPartner'),
        upd: p('UpdateAllowed', 'X'), chg: p('ChangeFixed'), dev: p('AllowedDeviation', '000'),
    ]))
    message.setHeader('AllRatesRateDate', last.rate_date)
    message.setHeader('AllRatesSource', last.bank)
    message.setHeader('AllRatesRows', rows.size())
    return message
}

static Map<String, BigDecimal> parseFactors(String s) {
    s.split(',').findAll { it.contains(':') }.collectEntries {
        def (c, f) = it.split(':')*.trim(); [(c.toUpperCase()): new BigDecimal(f)]
    }
}

// Two-sided tables carry several rows per currency (CBIC and Thai Customs: import and
// export; URA: import, export, vat, income_tax). One TCURR rate type takes one of them:
// TableRateType picks it; otherwise the API's own order applies (import before export).
static int typeRank(t) {
    int i = ['reference', 'mid', 'import', 'buy', 'sell', 'export'].indexOf(t as String)
    i < 0 ? 99 : i
}

/** Table rows -> E1BP1093_0 field maps. Pure, so it can be tested outside CPI. */
static List<Map> buildRows(body, Map opt) {
    String validFrom = (body.rate_date as String).replace('-', '')
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
        // EXCH_RATE is DEC 9,5 in BAPI1093_0: rates for `factor` units, 5 decimals.
        BigDecimal rate = (v * factor).setScale(5, java.math.RoundingMode.HALF_UP)
        if (rate.signum() <= 0 || rate.precision() - rate.scale() > 4) continue // does not fit DEC 9,5
        out << [RATE_TYPE: opt.rateType, FROM_CURR: from, TO_CURRNCY: to, VALID_FROM: validFrom,
                EXCH_RATE: rate.stripTrailingZeros().toPlainString(),
                FROM_FACTOR: (from == foreign ? factor : BigDecimal.ONE).toPlainString(),
                TO_FACTOR: (to == foreign ? factor : BigDecimal.ONE).toPlainString()]
    }
    out
}

static String toIdocXml(List<Map> rows, Map h) {
    def w = new StringWriter()
    new MarkupBuilder(w).with { b ->
        b.EXCHANGE_RATE01 {
            IDOC(BEGIN: '1') {
                EDI_DC40(SEGMENT: '1') {
                    TABNAM('EDI_DC40'); IDOCTYP('EXCHANGE_RATE01'); MESTYP('EXCHANGE_RATE')
                    SNDPOR(h.sndpor); SNDPRT('LS'); SNDPRN(h.sndprn)
                    RCVPOR(h.rcvpor); RCVPRT('LS'); RCVPRN(h.rcvprn)
                }
                E1EXCHANGE_RATE(SEGMENT: '1') {
                    UPD_ALLOW(h.upd); CHG_FIXED(h.chg); DEV_ALLOW(h.dev)
                    rows.each { r ->
                        E1BP1093_0(SEGMENT: '1') {
                            ['RATE_TYPE', 'FROM_CURR', 'TO_CURRNCY', 'VALID_FROM', 'EXCH_RATE', 'FROM_FACTOR', 'TO_FACTOR'].each { f -> "$f"(r[f]) }
                        }
                    }
                }
            }
        }
    }
    w.toString()
}
