# SAP official & customs exchange rates

Load **official exchange rates** — customs valuation rates for 66 clearance countries, central bank reference rates from 124 central banks, and tax authority tables — from the [AllRatesToday API](https://allratestoday.com/customs-exchange-rate-api/) into SAP S/4HANA, SAP ECC and SAP Global Trade Services (GTS).

It covers every SAP edition:

| Your SAP | Route | In this repo |
|---|---|---|
| **ECC, S/4HANA on-premise or private cloud** | SAP Integration Suite → `EXCHANGE_RATE01` IDoc → TCURR, through Cloud Connector | iFlow **AllRatesToday_Official_Rates_to_SAP_IDoc** |
| **S/4HANA Cloud Public Edition** | SAP Integration Suite → Currency Exchange Rate API (OData V4, `SAP_COM_0B18`); needs no extra SAP subscription | iFlow **AllRatesToday_Official_Rates_to_S4HANA_Cloud** |
| **S/4HANA Cloud with SAP Market Rates Management** | SAP Integration Suite → Bring Your Own Rates upload API, which S/4HANA pulls from | iFlow **AllRatesToday_Official_Rates_to_SAP_MRM_BYOR** |
| **On-premise, without Integration Suite** | ABAP report → `BAPI_EXCHRATE_CREATEMULTIPLE` | [`abap/zallrates_load_exchange_rates.prog.abap`](abap/zallrates_load_exchange_rates.prog.abap) |

Every route uses the authority's own table date (`rate_date`) as the valid-from date. Rates are stored under the exchange rate type you choose, for example `M`, or `ZCUS` for customs.

**Status:**
- **Groovy scripts:** unit-tested with Groovy 4 and stand-ins for the CPI APIs (see [Tests](#tests)).
- **iFlow artifacts:** they follow the layout of SAP's own exchange-rate recipe. We checked that the XML is well-formed and every parameter is defined, but **we have not yet deployed them in an SAP tenant**.
- **ABAP report:** not yet run in an SAP system.

Import into a test tenant first, and [tell us](https://github.com/AllRates-Today/sap-official-exchange-rates/issues) what you find.

## Ready-to-import package (Integration Suite)

Download **AllRatesToday_SAP_Integration_Suite_v1.1.0.zip** from the [latest release](https://github.com/AllRates-Today/sap-official-exchange-rates/releases/latest). It contains one zip per iFlow. Nothing gets installed in the SAP system itself.

1. Create a package under **Design → Integrations and APIs → Create**, for example *AllRatesToday Exchange Rates*. Then go to **Artifacts → Add → Integration Flow → Upload** and pick the iFlow zip for your landscape.
2. Under **Monitor → Security Material → Create → Secure Parameter**, create `AllRatesToday_API_Key` holding your API key ([get one](https://allratestoday.com/register/)).
3. **Configure** the iFlow. Every setting is externalized, so you don't edit the flow itself:

| Parameter | Meaning | Default |
|---|---|---|
| `Schedule` | Timer. Set it to run after your source publishes (ECB about 16:00 CET; RBI 13:30 IST; monthly customs tables near month end) | daily 16:30 UTC |
| `API_Key_Alias` | Secure Parameter that holds the key | `AllRatesToday_API_Key` |
| `Source` | Central bank or tax authority code, such as `ecb`, `rbi`, `hmrc`, `bazg` or `boc` ([list](https://allratestoday.com/central-bank-rates-api/)) | `ecb` |
| `Customs_Country` | ISO code for customs valuation rates (`NL`, `US`, `IN`, ...). Takes priority over `Source` | `-` |
| `Rate_Type` | Exchange rate type (KURST) | `M` |
| `Home_Currency` | Writes every rate as foreign → home (direct quotation) | `-` (the table's own direction) |
| `Currencies` | Comma-separated list to load, e.g. `USD,GBP,JPY` | `-` (all) |
| `Factors` | Ratios matching OBBS/TCURF, e.g. `JPY:100,KRW:1000` | `-` |
| `Table_Rate_Type` | Two-sided tables (CBIC, Thai Customs, Korea Customs, URA, Iceland): `import` or `export` | `-` (import) |
| `Lookback_Days` | `0` loads the latest table. `1`–`366` loads every table published in the last N days (backfill; paid plans; one API call per day). Set it back to `0` after the run | `0` |

`-` means "not set", because a Content Modifier constant cannot be empty.

4. Fill in the connection parameters for your flow (below) and **Deploy**. Watch the run under **Monitor → Message Processing**. A failed run carries the error text from the API or from SAP.

Use one configured copy per source. For example, to load the ECB table into `M` and the Dutch customs table into `ZCUS`, upload the iFlow twice under different names.

### ECC / S/4HANA on-premise (IDoc)

| Parameter | Value |
|---|---|
| `IDoc_Address` | `http://<virtual host>:<port>/sap/bc/srt/idoc?sap-client=<client>` (the Cloud Connector virtual host) |
| `IDoc_Location_ID` | Cloud Connector location ID, if any |
| `IDoc_Credentials` | User Credentials artifact for the IDoc user |
| `Sender_Port`, `Sender_Partner`, `Receiver_Port`, `Receiver_Partner` | EDI_DC40 partner data. These must match WE20 ([backend setup](#sap-backend)) |

### S/4HANA Cloud Public Edition (Currency Exchange Rate API)

In S/4HANA Cloud:
1. Create a **Communication System** with an inbound user that uses the client certificate from your Cloud Integration keystore.
2. Go to **Communication Arrangements → New** and choose **`SAP_COM_0B18`** (Finance - Currency Exchange Rates Integration).

| Parameter | Value |
|---|---|
| `S4_Host` | API host of the tenant, e.g. `https://my000000-api.s4hana.cloud.sap` |
| `S4_Authentication` | `Client Certificate` (X.509), or `Basic` if your arrangement allows a user and password |
| `S4_Private_Key_Alias` | Keystore alias of the client certificate |
| `S4_Credentials` | User Credentials artifact (Basic only) |

**What the flow does:**
1. Fetches a CSRF token.
2. Posts one `$batch` to `/sap/opu/odata4/sap/api_currencyexchangerate/srvd_a2x/sap/currencyexchangerate/0001/`. It contains one `POST CurrencyExchangeRate` per rate, each in its own change set, with these fields: `ExchangeRateType`, `SourceCurrency`, `TargetCurrency`, `ExchangeRateEffectiveDate`, `AbsoluteExchangeRate`, `NumberOfSourceCurrencyUnits`, `NumberOfTargetCurrencyUnits`.
3. Counts a rate that already exists for that date as skipped, so reruns are safe. If S/4HANA rejects a rate for any other reason, the run fails with S/4HANA's message.

The rates then appear in the **Currency Exchange Rates** app.

### S/4HANA Cloud with Market Rates Management (BYOR)

This route needs an SAP Market Rates Management, Bring Your Own Rates subscription. That is a paid BTP service; its free plan allows 100 records per request.

| Parameter | Value |
|---|---|
| `BYOR_Upload_URL` | `uploadUrl` from the service key |
| `BYOR_OAuth_Credentials` | OAuth2 Client Credentials artifact holding the token URL, client ID and secret from the service key |
| `BYOR_Provider` | `Y001` (customer-managed) or `Y002` (SAP-managed) |
| `BYOR_Property` | Market data property, default `CMID` |
| `BYOR_Max_Records` | `1500` on the default plan, `100` on the free plan |

Each record is sent with `marketDataSource` `BYOR`, category `01`, `key1`/`key2` set to the from/to currency, and `effectiveTime` `00:00:00` GMT.

In S/4HANA:
1. Set up communication arrangements `SAP_COM_0203` and `SAP_COM_0841`.
2. Map the notations in *Assign Currency Notations - Datafeed*.
3. Schedule *Request Current Market Data*.

Unless you already run Market Rates Management, use the direct API route above.

### Rebuild the zips

```bash
python3 package/build.py   # dist/*.zip from integration-suite/*.groovy
```

## Which endpoint

| Need | Endpoint |
|---|---|
| Customs valuation rate for goods cleared in a country | `GET https://allratestoday.com/api/v1/customs/{country}/latest` (ISO code, e.g. `NL`, `US`, `CN`) |
| The customs table in force on a past declaration date | `GET .../api/v1/customs/{country}/{YYYY-MM-DD}` (paid plans) |
| A central bank or tax authority table (ECB, HMRC, RBI, ...) | `GET .../api/v1/central-bank/{code}/latest` |
| Which authority covers which country | `GET .../api/v1/customs` (no key) |

Send the key as `Authorization: Bearer <key>`. Every response carries `rate_date`, the date of the authority's own table, which both routes use as `VALID_FROM`.

## Building the IDoc iFlow yourself

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
| `TableRateType` | Side of a two-sided table: `import` or `export` (CBIC, Thai Customs, Korea Customs, Iceland, URA) | one row per currency, import first |
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

`ZALLRATES_LOAD_EXCHANGE_RATES` calls the API with `CL_HTTP_CLIENT`, parses it with `/UI2/CL_JSON`, writes every rate as foreign → `P_HOME` and posts it with `BAPI_EXCHRATE_CREATEMULTIPLE` followed by `BAPI_TRANSACTION_COMMIT`. Selection screen: clearance country **or** source code, date (empty = today's table), home currency, rate type, optional currency list and ratios, table side (`P_RTYP`: `import` or `export` for two-sided tables; empty = one row per currency, import first), test mode (on by default).

**Two-sided tables.** India (CBIC), Thailand, Korea, Iceland and Uganda publish separate import and export rates for each currency. Load each side into its own rate type (for example `ZCIN` with `import`, `ZCIX` with `export`) and assign each to the matching legal regulation in GTS.

Prerequisites: the SSL client PSE (STRUST) must trust allratestoday.com's certificate chain; replace `GET_API_KEY` with your secure storage; translation ratios as above. Schedule it with SM36 on the source's cadence.

## SAP GTS (customs)

GTS calculates duty from TCURR under the exchange rate type assigned to each legal regulation (customizing *Control Setting for "Customs Processing" Service*). Load the customs table into that rate type in the feeder system by either route above, and distribute it to GTS the standard way (report `RFALEX00`, message type `EXCHANGE_RATE` over ALE). Step by step: [Customs rates in SAP GTS](https://allratestoday.com/docs/integrations/sap-gts/).

## Tests

The Groovy scripts are tested with plain Groovy 4 and stand-ins for the CPI `Message` API:

```bash
CP="groovy-4.0.24.jar:groovy-json-4.0.24.jar:groovy-xml-4.0.24.jar"
java -cp "$CP:test" groovy.ui.GroovyMain test/run.groovy     # ExchangeRateToIdoc: ok
java -cp "$CP:test" groovy.ui.GroovyMain test/fetch.groovy   # FetchRates: ok
java -cp "$CP:test" groovy.ui.GroovyMain test/cloud.groovy   # ExchangeRateToS4Cloud: ok
```

The tests cover direction and inversion, ratios, the currency filter, rates too large for DEC 9,5, IDoc control data, two-sided tables, the backfill window (one call per day, duplicate tables dropped by `rate_date`, 404 days skipped, API errors surfaced), the `$batch` body and response handling, and the BYOR payload and record limit.

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
