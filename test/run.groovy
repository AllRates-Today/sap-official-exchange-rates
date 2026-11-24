// java -cp "<groovy jars>" groovy.ui.GroovyMain -cp test:integration-suite test/run.groovy
import com.sap.gateway.ip.core.customdev.util.Message
import groovy.xml.XmlSlurper

def script = new GroovyShell(this.class.classLoader).parse(new File('integration-suite/ExchangeRateToIdoc.groovy'))
def assertEq = { a, b, m -> assert a == b : "$m: expected $b, got $a" }

// German customs (Zoll): table quoted per 1 EUR -> written as foreign -> EUR
def zoll = '{"bank":"zollde","rate_date":"2026-11-01","rates":[{"base":"EUR","quote":"USD","type":"monthly","value":1.1234},{"base":"EUR","quote":"JPY","type":"monthly","value":172.35},{"base":"EUR","quote":"GBP","type":"monthly","value":0.8711}]}'
def m = new Message(body: zoll, props: [RateType: 'ZCUS', HomeCurrency: 'EUR', Factors: 'JPY:100', Currencies: 'USD,JPY',
                                       SenderPort: 'SAPCPI', SenderPartner: 'CPI_LS', ReceiverPort: 'SAPS4H', ReceiverPartner: 'S4H_100'])
script.processData(m)
def x = new XmlSlurper().parseText(m.getBody(String))
def segs = x.IDOC.E1EXCHANGE_RATE.E1BP1093_0
assertEq(segs.size(), 2, 'GBP filtered out')
def usd = segs.find { it.FROM_CURR.text() == 'USD' }
assertEq(usd.TO_CURRNCY.text(), 'EUR', 'direction')
assertEq(usd.EXCH_RATE.text(), '0.89015', 'USD inverse, 5 decimals')
assertEq(usd.VALID_FROM.text(), '20261101', 'valid from = table date')
assertEq(usd.RATE_TYPE.text(), 'ZCUS', 'rate type')
def jpy = segs.find { it.FROM_CURR.text() == 'JPY' }
assertEq(jpy.FROM_FACTOR.text(), '100', 'JPY factor')
assertEq(jpy.EXCH_RATE.text(), '0.58021', '100 JPY in EUR')
assertEq(x.IDOC.EDI_DC40.IDOCTYP.text(), 'EXCHANGE_RATE01', 'idoc type')
assertEq(x.IDOC.EDI_DC40.RCVPRN.text(), 'S4H_100', 'receiver partner')
assertEq(x.IDOC.E1EXCHANGE_RATE.UPD_ALLOW.text(), 'X', 'update allowed default')
assertEq(m.@headers.AllRatesRateDate, '2026-11-01', 'header')

// Japan Customs: yen per unit, kept as published (no HomeCurrency)
def jp = '{"bank":"jpcustoms","rate_date":"2026-11-23","rates":[{"base":"USD","quote":"JPY","type":"import","value":154.32}]}'
def m2 = new Message(body: jp, props: [SenderPort: 'P', SenderPartner: 'S', ReceiverPort: 'R', ReceiverPartner: 'T'])
script.processData(m2)
def s2 = new XmlSlurper().parseText(m2.getBody(String)).IDOC.E1EXCHANGE_RATE.E1BP1093_0
assertEq(s2.EXCH_RATE.text(), '154.32', 'as published')
assertEq(s2.RATE_TYPE.text(), 'M', 'default rate type')

// Too large for DEC 9,5 (e.g. 1 EUR = 41,000 IRR without a factor) is skipped, not truncated
def big = '{"bank":"x","rate_date":"2026-11-01","rates":[{"base":"EUR","quote":"IRR","value":41000},{"base":"EUR","quote":"USD","value":1.1}]}'
def m3 = new Message(body: big, props: [SenderPort: 'P', SenderPartner: 'S', ReceiverPort: 'R', ReceiverPartner: 'T'])
script.processData(m3)
assertEq(new XmlSlurper().parseText(m3.getBody(String)).IDOC.E1EXCHANGE_RATE.E1BP1093_0.size(), 1, 'IRR skipped')

println 'ExchangeRateToIdoc: ok'
