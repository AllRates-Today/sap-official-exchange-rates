// java -cp "<groovy jars>:test" groovy.ui.GroovyMain test/cloud.groovy
import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonSlurper

def assertEq = { a, b, m -> assert a == b : "$m: expected $b, got $a" }
def cloud = new GroovyShell(this.class.classLoader).parse(new File('integration-suite/ExchangeRateToS4Cloud.groovy'))
def idoc = new GroovyShell(this.class.classLoader).parse(new File('integration-suite/ExchangeRateToIdoc.groovy'))

def tables = '{"tables":[' +
  '{"bank":"ecb","rate_date":"2026-11-19","rates":[{"base":"EUR","quote":"USD","value":1.0512},{"base":"EUR","quote":"JPY","value":163.2}]},' +
  '{"bank":"ecb","rate_date":"2026-11-20","rates":[{"base":"EUR","quote":"USD","value":1.0498},{"base":"EUR","quote":"JPY","value":162.9}]}]}'
def props = [RateType: 'M', HomeCurrency: 'EUR', Factors: 'JPY:100', Currencies: '-']

// same rows as the IDoc mapping
def opt = [rateType: 'M', home: 'EUR', keep: [] as Set, factors: [JPY: 100G], side: '']
def body = new JsonSlurper().parseText(tables)
def a = body.tables.collectMany { idoc.buildRows(it, opt) }
def b = body.tables.collectMany { cloud.buildRows(it, opt) }.collect { it.findAll { k, v -> k != 'VALID_FROM_ISO' } }
assertEq(b, a, 'cloud rows == IDoc rows')

// Route A: $batch body
def m = new Message(body: tables, props: props.clone())
cloud.processData(m)
String batch = m.@props.AllRatesBatchBody
assertEq((batch =~ /POST CurrencyExchangeRate HTTP\/1.1/).size(), 4, 'one POST per rate')
assertEq((batch =~ /--changeset_\d+--/).size(), 4, 'one change set per rate')
assert batch.endsWith('--batch_allratestoday--\r\n')
def first = new JsonSlurper().parseText(batch.readLines().find { it.startsWith('{') })
assertEq(first, [ExchangeRateType: 'M', SourceCurrency: 'USD', TargetCurrency: 'EUR', ExchangeRateEffectiveDate: '2026-11-19',
                 AbsoluteExchangeRate: 0.95129, NumberOfSourceCurrencyUnits: 1, NumberOfTargetCurrencyUnits: 1], 'entity')
def jpy = batch.readLines().findAll { it.startsWith('{') }.collect { new JsonSlurper().parseText(it) }.find { it.SourceCurrency == 'JPY' }
assertEq(jpy.NumberOfSourceCurrencyUnits, 100, 'JPY ratio')
assertEq(m.@headers['x-csrf-token'], 'Fetch', 'token fetch header')
assertEq(m.getBody(String), '', 'GET has no body')
cloud.prepareBatch(m)
assertEq(m.getBody(String), batch, 'batch restored')
assertEq(m.@headers['Content-Type'], 'multipart/mixed; boundary=batch_allratestoday', 'content type')

// checkBatch: created / already exists / real error
def resp = { parts -> '--batchresponse_1\r\n' + parts.collect { st, txt ->
    "Content-Type: multipart/mixed; boundary=changesetresponse_x\r\n\r\n--changesetresponse_x\r\nContent-Type: application/http\r\nContent-Transfer-Encoding: binary\r\n\r\n" +
    "HTTP/1.1 $st X\r\nContent-Type: application/json\r\n\r\n$txt\r\n--changesetresponse_x--\r\n" }.join('--batchresponse_1\r\n') + '--batchresponse_1--\r\n' }
def ok = new Message(body: resp([[201, '{"ExchangeRateType":"M"}'], [201, '{}'], [400, '{"error":{"message":"Exchange rate already exists"}}'], [201, '{}']]), props: m.@props)
cloud.checkBatch(ok)
assertEq(ok.@headers.AllRatesCreated, 3, 'created')
assertEq(ok.@headers.AllRatesSkipped, 1, 'skipped')
def bad = new Message(body: resp([[201, '{}'], [400, '{"error":{"message":"Currency XYZ is not defined"}}']]), props: m.@props)
try { cloud.checkBatch(bad); assert false } catch (IllegalStateException e) {
    assert e.message.contains('JPY EUR 2026-11-19: HTTP 400') : e.message
}

// Route B: BYOR payload
def m2 = new Message(body: tables, props: props + [ByorProvider: '-'])
cloud.toByor(m2)
def recs = new JsonSlurper().parseText(m2.getBody(String))
assertEq(recs.size(), 4, 'records')
assertEq(recs[0].subMap(['providerCode', 'marketDataSource', 'marketDataCategory', 'key1', 'key2', 'marketDataProperty', 'effectiveDate', 'effectiveTime', 'marketDataValue']),
         [providerCode: 'Y001', marketDataSource: 'BYOR', marketDataCategory: '01', key1: 'USD', key2: 'EUR', marketDataProperty: 'CMID',
          effectiveDate: '2026-11-19', effectiveTime: '00:00:00', marketDataValue: 0.95129], 'BYOR record')
assertEq(recs.find { it.key1 == 'JPY' }.fromFactor, 100, 'BYOR ratio')
try { cloud.toByor(new Message(body: tables, props: props + [ByorMaxRecords: '3'])); assert false } catch (IllegalStateException e) { assert e.message.contains('limit of 3') }
println 'ExchangeRateToS4Cloud: ok'
