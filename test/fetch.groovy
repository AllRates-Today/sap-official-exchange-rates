// java -cp "<groovy jars>:test" groovy.ui.GroovyMain test/fetch.groovy
import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonSlurper
import groovy.xml.XmlSlurper

def assertEq = { a, b, m -> assert a == b : "$m: expected $b, got $a" }
def load = { Map bind ->
    def s = new GroovyShell(this.class.classLoader, new Binding(bind)).parse(new File('integration-suite/FetchRates.groovy'))
    s
}
def seen = []
// ECB-like source: no tables at weekends, the dated endpoint returns Friday's table
def tableFor = { String d ->
    def day = java.time.LocalDate.parse(d).dayOfWeek.value
    def eff = day == 6 ? java.time.LocalDate.parse(d).minusDays(1) : day == 7 ? java.time.LocalDate.parse(d).minusDays(2) : java.time.LocalDate.parse(d)
    """{"bank":"ecb","rate_date":"$eff","rates":[{"base":"EUR","quote":"USD","value":1.1}]}"""
}
def http = { String url, String key ->
    seen << url
    assert key == 'art_live_test'
    if (url.endsWith('/latest')) return [status: 200, body: tableFor('2026-11-26')]
    def d = url.tokenize('/').last()
    if (d < '2026-11-01') return [status: 404, body: '{"error":"none"}']
    [status: 200, body: tableFor(d)]
}

// 1. daily: one call to latest
def m = new Message(body: '', props: [Source: 'ECB'])
load([httpGet: http, apiKeyOverride: 'art_live_test']).processData(m)
assertEq(seen, ['https://allratestoday.com/api/v1/central-bank/ecb/latest'], 'latest url')
assertEq(new JsonSlurper().parseText(m.getBody(String)).tables.size(), 1, 'one table')
assertEq(m.@props.AllRatesCalls, 1, 'calls')

// 2. backfill 7 days ending Sunday 2026-11-22: 8 calls, 6 distinct tables (the 15th returns Friday the 13th)
seen.clear()
def m2 = new Message(body: '', props: [Source: 'ecb', LookbackDays: '7', AsOfDate: '2026-11-22'])
load([httpGet: http, apiKeyOverride: 'art_live_test']).processData(m2)
assertEq(seen.size(), 8, 'one call per day')
assertEq(seen.first(), 'https://allratestoday.com/api/v1/central-bank/ecb/2026-11-15', 'window start')
def t2 = new JsonSlurper().parseText(m2.getBody(String)).tables
assertEq(t2*.rate_date, ['2026-11-13', '2026-11-16', '2026-11-17', '2026-11-18', '2026-11-19', '2026-11-20'], 'deduped by rate_date, in order')

// 3. customs country wins over Source; 404 days skipped
seen.clear()
def m3 = new Message(body: '', props: [Source: 'ecb', CustomsCountry: 'nl', LookbackDays: '2', AsOfDate: '2026-11-01'])
load([httpGet: http, apiKeyOverride: 'art_live_test']).processData(m3)
assertEq(seen[0], 'https://allratestoday.com/api/v1/customs/NL/2026-10-30', 'customs path')
assertEq(new JsonSlurper().parseText(m3.getBody(String)).tables*.rate_date, ['2026-10-30'], '404 days skipped')

// 4. errors surface with the status
def bad = { u, k -> [status: 403, body: '{"error":"paid plans only"}'] }
try { load([httpGet: bad, apiKeyOverride: 'k']).processData(new Message(body: '', props: [Source: 'ecb', LookbackDays: '3'])); assert false }
catch (IllegalStateException e) { assert e.message.contains('HTTP 403') && e.message.contains('paid plans only') }
try { load([httpGet: http, apiKeyOverride: 'k']).processData(new Message(body: '', props: [:])); assert false }
catch (IllegalArgumentException e) { assert e.message.contains('Source') }

// 5. fetch -> IDoc end to end
def idoc = new GroovyShell(this.class.classLoader).parse(new File('integration-suite/ExchangeRateToIdoc.groovy'))
m2.@props.putAll([HomeCurrency: 'EUR', SenderPort: 'P', SenderPartner: 'S', ReceiverPort: 'R', ReceiverPartner: 'T'])
idoc.processData(m2)
def segs = new XmlSlurper().parseText(m2.getBody(String)).IDOC.E1EXCHANGE_RATE.E1BP1093_0
assertEq(segs.size(), 6, 'one row per table')
assertEq(segs*.VALID_FROM*.text(), ['20261113', '20261116', '20261117', '20261118', '20261119', '20261120'], 'valid from per table')
assertEq(m2.@headers.AllRatesRateDate, '2026-11-20', 'latest date header')
println 'FetchRates: ok'
