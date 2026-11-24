# SAP official & customs exchange rates

Load **official exchange rates** — customs valuation rates for 66 clearance countries, central bank reference rates from 124 central banks, and tax authority tables — from the [AllRatesToday API](https://allratestoday.com/customs-exchange-rate-api/) into SAP S/4HANA, SAP ECC and SAP Global Trade Services (GTS).

Two routes, both writing to table **TCURR** under the exchange rate type you choose (for example `ZCUS` for customs, `M` for the standard rate):

| Route | For | What is in this repo |
|---|---|---|
| **SAP Integration Suite** (Cloud Integration) | S/4HANA and ECC, on-premise or private cloud, through Cloud Connector | [`integration-suite/ExchangeRateToIdoc.groovy`](integration-suite/ExchangeRateToIdoc.groovy): API response → `EXCHANGE_RATE01` IDoc |
| **ABAP report** | On-premise systems that call the API directly | [`abap/zallrates_load_exchange_rates.prog.abap`](abap/zallrates_load_exchange_rates.prog.abap): API → `BAPI_EXCHRATE_CREATEMULTIPLE` |

Both are reference implementations: review them, run them in a test client, and adapt them to your standards. The Groovy mapping is unit-tested (see [Tests](#tests)); the ABAP report has not been run in an SAP system by us.

## Which endpoint

| Need | Endpoint |
|---|---|
| Customs valuation rate for goods cleared in a country | `GET https://allratestoday.com/api/v1/customs/{country}/latest` (ISO code, e.g. `NL`, `US`, `CN`) |
| The customs table in force on a past declaration date | `GET .../api/v1/customs/{country}/{YYYY-MM-DD}` (paid plans) |
| A central bank or tax authority table (ECB, HMRC, RBI, ...) | `GET .../api/v1/central-bank/{code}/latest` |
| Which authority covers which country | `GET .../api/v1/customs` (no key) |

Send the key as `Authorization: Bearer <key>`. Every response carries `rate_date`, the date of the authority's own table, which both routes use as `VALID_FROM`.

## Route 1: SAP Integration Suite → IDoc

The iFlow has four steps:

1. **Timer** start event, on the source's cadence (monthly EU customs tables: last days of the previous month; weekly Japan, Korea and US CBP; daily central banks after publication).
2. **Request Reply** to an **HTTP receiver**: `GET https://allratestoday.com/api/v1/customs/{country}/latest`, with the header `Authorization: Bearer {{AllRatesKey}}` (keep the key in a Secure Parameter and set the header in a Content Modifier).
3. **Content Modifier** setting the exchange properties below, then a **Groovy Script** step with `ExchangeRateToIdoc.groovy`.
4. **IDoc receiver adapter** to the SAP system.

### Script properties

| Property | Meaning | Default |
|---|---|---|
| `RateType` | TCURR exchange rate type (`KURST`) | `M` |
| `HomeCurrency` | Write every rate as foreign → home (direct quotation) | the table's own direction |
| `Currencies` | Comma list to keep, e.g. `USD,GBP,JPY` | all |
| `Factors` | Ratios matching OBBS/TCURF, e.g. `JPY:100,KRW:1000` | 1 |
| `SenderPort`, `SenderPartner`, `ReceiverPort`, `ReceiverPartner` | EDI_DC40 partner data (partner type `LS`) | — |
| `UpdateAllowed` / `ChangeFixed` / `AllowedDeviation` | `UPD_ALLOW` / `CHG_FIXED` / `DEV_ALLOW` | `X` / blank / `000` |

Rates are written with 5 decimals (`EXCH_RATE` is DEC 9,5). A rate that does not fit (above 9999.99999 for the chosen factor) is skipped rather than truncated: give that currency a ratio in `Factors` and OBBS.

### IDoc receiver adapter

As in SAP's own exchange-rate recipe on the [SAP Integration Recipes repository](https://github.com/SAP/apibusinesshub-integration-recipes/tree/master/Recipes/for/saps4hanaintegrationwithbloombergbank):

- Address: `http://<virtual host>:<port>/sap/bc/srt/idoc?sap-client=<client>` (HTTP, the Cloud Connector virtual host)
- Proxy Type: **On-Premise**, with your Cloud Connector **Location ID**
- Authentication: **Basic** (User Credentials artifact)
- IDoc Content Type: **Text/XML**

### SAP backend

- **SRTIDOC**: register the IDoc SOAP inbound service (default values).
- **SICF**: activate `/sap/bc/srt/idoc`.
- **WE20**: an inbound partner profile for the sender logical system with message type **`EXCHANGE_RATE`** (basic type **`EXCHANGE_RATE01`**). Sender partner, partner type and port in EDI_DC40 must match it, or the IDoc stops in status 56.
- **OBBS**: translation ratios for every currency pair in the rate type (for example 100 JPY), matching the `Factors` property.

## Route 2: ABAP report

`ZALLRATES_LOAD_EXCHANGE_RATES` calls the API with `CL_HTTP_CLIENT`, parses it with `/UI2/CL_JSON`, writes every rate as foreign → `P_HOME` and posts it with `BAPI_EXCHRATE_CREATEMULTIPLE` followed by `BAPI_TRANSACTION_COMMIT`. Selection screen: clearance country **or** source code, date (empty = today's table), home currency, rate type, optional currency list and ratios, test mode (on by default).

Prerequisites: the SSL client PSE (STRUST) must trust allratestoday.com's certificate chain; replace `GET_API_KEY` with your secure storage; translation ratios as above. Schedule it with SM36 on the source's cadence.

## SAP GTS (customs)

GTS calculates duty from TCURR under the exchange rate type assigned to each legal regulation (customizing *Control Setting for "Customs Processing" Service*). Load the customs table into that rate type in the feeder system by either route above, and distribute it to GTS the standard way (report `RFALEX00`, message type `EXCHANGE_RATE` over ALE). Step by step: [Customs rates in SAP GTS](https://allratestoday.com/docs/integrations/sap/#gts).

## S/4HANA Cloud Public Edition

Not covered by this repo. Public Edition takes exchange rates through SAP Market Rates Management (Bring Your Own Rates) or the Currency Exchange Rate API (`API_CURRENCYEXCHANGERATE`, communication scenario `SAP_COM_0B18`). Check the field names on the SAP Business Accelerator Hub for your release; send direct quotations only. [Contact us](https://allratestoday.com/contact?tab=business&topic=tax_authority_data) if you need this route.

## Tests

The Groovy mapping is tested with plain Groovy 4 and a stand-in for the CPI `Message` class:

```bash
CP="groovy-4.0.24.jar:groovy-json-4.0.24.jar:groovy-xml-4.0.24.jar"
java -cp "$CP" org.codehaus.groovy.tools.FileSystemCompiler -d build test/com/sap/gateway/ip/core/customdev/util/Message.groovy
java -cp "$CP:build" groovy.ui.GroovyMain test/run.groovy   # ExchangeRateToIdoc: ok
```

## 🔗 Links

- **Website:** [allratestoday.com](https://allratestoday.com)
- [Customs Exchange Rate API](https://allratestoday.com/customs-exchange-rate-api/)
- [SAP integration guide](https://allratestoday.com/docs/integrations/sap/)
- [Central Bank Exchange Rate API](https://allratestoday.com/central-bank-rates-api/)
- [Node.js package: customs-exchange-rates](https://www.npmjs.com/package/customs-exchange-rates)
- [Register (Free)](https://allratestoday.com/register)
- [GitHub](https://github.com/AllRates-Today/sap-official-exchange-rates)

## 📜 License

MIT
