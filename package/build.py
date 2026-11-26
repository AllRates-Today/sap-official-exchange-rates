#!/usr/bin/env python3
"""Build the importable SAP Integration Suite iFlow artifacts (dist/*.zip).

Each zip is a standard Cloud Integration "Integration Flow" artifact
(META-INF/MANIFEST.MF + src/main/resources/...), the same layout as SAP's own
exchange-rate recipe on github.com/SAP/apibusinesshub-integration-recipes.
Upload it in a package under Design > Add > Integration Flow > Upload.

    python3 package/build.py          # writes dist/<flow>.zip and dist/<package>.zip
"""
import os, shutil, zipfile
from xml.sax.saxutils import escape

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIST = os.path.join(ROOT, 'dist')
VERSION = '1.1.0'
SCRIPTS = os.path.join(ROOT, 'integration-suite')


def prop(k, v=''):
    v = escape(str(v))
    return f'<ifl:property><key>{k}</key><value>{v}</value></ifl:property>' if v != '' else f'<ifl:property><key>{k}</key><value/></ifl:property>'


def props(d):
    return ''.join(prop(k, v) for k, v in d.items())


def row(cells):
    """Content Modifier table row (stored XML-escaped inside the property value)."""
    return ''.join(f"<cell id='{k}'>{v}</cell>" for k, v in cells.items())


def cm_property(name, value, typ='constant'):
    return '<row>' + row({'Action': 'Create', 'Type': typ, 'Value': value, 'Default': '', 'Name': name, 'Datatype': ''}) + '</row>'


def schedule_value():
    cells = [('dateType', 'DAILY'), ('timeType', 'ON_TIME'), ('dayValue', ''), ('monthValue', ''), ('yearValue', ''),
             ('hourValue', '16'), ('minutesValue', '30'), ('secondValue', '0'),
             ('timeZone', '( UTC 0:00 ) Greenwich Mean Time(Etc/GMT)'), ('triggerType', 'cron'),
             ('noOfSchedules', '1'), ('schedule1', '0+30+16+?+*+*+*&amp;trigger.timeZone=Etc/GMT')]
    return ''.join(f'<row><cell>{k}</cell><cell>{v}</cell></row>' for k, v in cells)


# ---------------------------------------------------------------- parameters
COMMON_PARAMS = [
    # name, type, default, required, description
    ('Schedule', 'custom:schedule', schedule_value(), True, 'When to run. Default daily 16:30 UTC; set it after your source publishes.'),
    ('API_Key_Alias', 'xsd:string', 'AllRatesToday_API_Key', True, 'Name of the Secure Parameter (Monitor > Security Material) that holds your AllRatesToday API key.'),
    ('Source', 'xsd:string', 'ecb', False, 'Central bank or tax authority code, e.g. ecb, rbi, hmrc, bazg, boc. Ignored when Customs_Country is set.'),
    ('Customs_Country', 'xsd:string', '-', False, 'ISO country code for customs valuation rates, e.g. NL, US, IN. - = central bank rates from Source.'),
    ('Rate_Type', 'xsd:string', 'M', True, 'SAP exchange rate type (KURST), e.g. M, or ZCUS for customs.'),
    ('Home_Currency', 'xsd:string', '-', False, 'Write every rate as foreign -> home currency (direct quotation), e.g. EUR. - = the table\'s own direction.'),
    ('Currencies', 'xsd:string', '-', False, 'Comma list of currencies to load, e.g. USD,GBP,JPY. - = all.'),
    ('Factors', 'xsd:string', '-', False, 'Translation ratios matching OBBS / TCURF, e.g. JPY:100,KRW:1000. - = 1 for every currency.'),
    ('Table_Rate_Type', 'xsd:string', '-', False, 'Two-sided tables (CBIC, Thai Customs, Korea Customs, URA, Iceland): import or export. - = import.'),
    ('Lookback_Days', 'xsd:integer', '0', True, '0 = latest table only. 1-366 = load every table published in the last N days (backfill; paid plans). Set back to 0 after the backfill run.'),
]

IDOC_PARAMS = [
    ('IDoc_Address', 'xsd:string', 'http://<virtual host>:<port>/sap/bc/srt/idoc?sap-client=<client>', True, 'IDoc SOAP endpoint of the SAP system, through the Cloud Connector virtual host.'),
    ('IDoc_Location_ID', 'xsd:string', '', False, 'Cloud Connector location ID (empty if your subaccount has one connector).'),
    ('IDoc_Credentials', 'xsd:string', 'SAP_IDOC_USER', True, 'User Credentials artifact for the SAP RFC/IDoc user.'),
    ('Sender_Port', 'xsd:string', 'SAPCPI', True, 'EDI_DC40 SNDPOR.'),
    ('Sender_Partner', 'xsd:string', 'CPI_ALLRATES', True, 'EDI_DC40 SNDPRN: logical system with an inbound partner profile (WE20) for EXCHANGE_RATE.'),
    ('Receiver_Port', 'xsd:string', 'SAP<SID>', True, 'EDI_DC40 RCVPOR, e.g. SAPS4H.'),
    ('Receiver_Partner', 'xsd:string', '<SID>CLNT<client>', True, 'EDI_DC40 RCVPRN: logical system of the receiving client.'),
]


def propdef(params, refs):
    out = ['<?xml version="1.0" encoding="UTF-8"?><parameters>']
    for name, typ, _d, req, desc in params:
        out.append(f'<parameter><key/><name>{name}</name><type>{typ}</type><isRequired>{str(req).lower()}</isRequired>'
                   f'<constraint/><description>{escape(desc)}</description><additionalMetadata/></parameter>')
    out.append('<param_references>')
    for cat, attr, label, key in refs:
        out.append(f'<reference attribute_category="{cat}" attribute_id="{attr}" attribute_uilabel="{label}" param_key="{key}"/>')
    out.append('</param_references></parameters>')
    return ''.join(out)


def java_prop_escape(s):
    return s.replace('\\', '\\\\').replace(':', '\\:').replace('=', '\\=')


def parameters_prop(params):
    lines = ['#AllRatesToday iFlow configuration defaults']
    for name, _t, default, _r, _d in params:
        lines.append(f'{name}={java_prop_escape(default)}')
    return '\n'.join(lines) + '\n'


# ---------------------------------------------------------------- iflw
def iflw(flow):
    """flow: dict with steps (in order), receiver message flow, participants."""
    steps = flow['steps']
    shapes, edges, elems = [], [], []
    x = 160
    ids = []
    for i, s in enumerate(steps):
        sid = s['id']
        ids.append(sid)
        incoming = f'<bpmn2:incoming>SequenceFlow_{i}</bpmn2:incoming>' if i > 0 else ''
        outgoing = f'<bpmn2:outgoing>SequenceFlow_{i + 1}</bpmn2:outgoing>' if i < len(steps) - 1 else ''
        if s['kind'] == 'timer':
            elems.append(f'<bpmn2:startEvent id="{sid}" name="{s["name"]}">{outgoing}'
                         f'<bpmn2:timerEventDefinition id="TimerEventDefinition_1"><bpmn2:extensionElements>'
                         + props({'scheduleKey': '{{Schedule}}', 'componentVersion': '1.0',
                                  'cmdVariantUri': 'ctype::FlowstepVariant/cname::intermediatetimer/version::1.0.1',
                                  'activityType': 'StartTimerEvent'})
                         + '</bpmn2:extensionElements></bpmn2:timerEventDefinition></bpmn2:startEvent>')
            w, h, y = 32, 32, 154
        elif s['kind'] == 'end':
            elems.append(f'<bpmn2:endEvent id="{sid}" name="{s["name"]}"><bpmn2:extensionElements>'
                         + props({'componentVersion': '1.1', 'cmdVariantUri': 'ctype::FlowstepVariant/cname::MessageEndEvent/version::1.1.0'})
                         + f'</bpmn2:extensionElements>{incoming}<bpmn2:messageEventDefinition/></bpmn2:endEvent>')
            w, h, y = 32, 32, 154
        elif s['kind'] == 'cm':
            table = ''.join(cm_property(n, v) for n, v in s['properties'])
            headers = ''.join(cm_property(n, v) for n, v in s.get('headers', []))
            elems.append(f'<bpmn2:callActivity id="{sid}" name="{s["name"]}"><bpmn2:extensionElements>'
                         + props({'bodyType': 'expression', 'propertyTable': table, 'headerTable': headers, 'wrapContent': '',
                                  'componentVersion': '1.5', 'activityType': 'Enricher',
                                  'cmdVariantUri': 'ctype::FlowstepVariant/cname::Enricher/version::1.5.1'})
                         + f'</bpmn2:extensionElements>{incoming}{outgoing}</bpmn2:callActivity>')
            w, h, y = 100, 60, 140
        elif s['kind'] == 'script':
            elems.append(f'<bpmn2:callActivity id="{sid}" name="{s["name"]}"><bpmn2:extensionElements>'
                         + props({'scriptFunction': s.get('function', ''), 'scriptBundleId': '', 'componentVersion': '1.1', 'activityType': 'Script',
                                  'cmdVariantUri': 'ctype::FlowstepVariant/cname::GroovyScript/version::1.1.2',
                                  'subActivityType': 'GroovyScript', 'script': s['script']})
                         + f'</bpmn2:extensionElements>{incoming}{outgoing}</bpmn2:callActivity>')
            w, h, y = 100, 60, 140
        elif s['kind'] == 'call':
            elems.append(f'<bpmn2:serviceTask id="{sid}" name="{s["name"]}"><bpmn2:extensionElements>'
                         + props({'componentVersion': '1.0', 'activityType': 'ExternalCall',
                                  'cmdVariantUri': 'ctype::FlowstepVariant/cname::ExternalCall/version::1.0.4'})
                         + f'</bpmn2:extensionElements>{incoming}{outgoing}</bpmn2:serviceTask>')
            w, h, y = 100, 60, 140
        shapes.append((sid, x, y, w, h))
        x += w + 60
    for i in range(1, len(steps)):
        elems.append(f'<bpmn2:sequenceFlow id="SequenceFlow_{i}" sourceRef="{ids[i - 1]}" targetRef="{ids[i]}"/>')
        a, b = shapes[i - 1], shapes[i]
        edges.append((f'SequenceFlow_{i}', a, b))

    calls = [s for s in steps if s['kind'] == 'call']
    width = x + 40
    di = ['<bpmndi:BPMNDiagram id="BPMNDiagram_1" name="Default Collaboration Diagram">'
          '<bpmndi:BPMNPlane bpmnElement="Collaboration_1" id="BPMNPlane_1">',
          f'<bpmndi:BPMNShape bpmnElement="Participant_Process_1" id="BPMNShape_Participant_Process_1"><dc:Bounds height="220.0" width="{width}.0" x="100.0" y="60.0"/></bpmndi:BPMNShape>']
    for sid, sx, sy, w, h in shapes:
        di.append(f'<bpmndi:BPMNShape bpmnElement="{sid}" id="BPMNShape_{sid}"><dc:Bounds height="{h}.0" width="{w}.0" x="{sx}.0" y="{sy}.0"/></bpmndi:BPMNShape>')
    for fid, a, b in edges:
        di.append(f'<bpmndi:BPMNEdge bpmnElement="{fid}" id="BPMNEdge_{fid}" sourceElement="BPMNShape_{a[0]}" targetElement="BPMNShape_{b[0]}">'
                  f'<di:waypoint x="{a[1] + a[3]}.0" xsi:type="dc:Point" y="170.0"/><di:waypoint x="{b[1]}.0" xsi:type="dc:Point" y="170.0"/></bpmndi:BPMNEdge>')
    parts, flows = [], []
    for n, c in enumerate(calls, 1):
        rcv = c['receiver']
        cs = next(sh for sh in shapes if sh[0] == c['id'])
        cx = cs[1] + 50
        di.append(f'<bpmndi:BPMNShape bpmnElement="Participant_R{n}" id="BPMNShape_Participant_R{n}"><dc:Bounds height="140.0" width="100.0" x="{cs[1]}.0" y="340.0"/></bpmndi:BPMNShape>')
        di.append(f'<bpmndi:BPMNEdge bpmnElement="MessageFlow_R{n}" id="BPMNEdge_MessageFlow_R{n}" sourceElement="BPMNShape_{c["id"]}" targetElement="BPMNShape_Participant_R{n}">'
                  f'<di:waypoint x="{cx}.0" xsi:type="dc:Point" y="200.0"/><di:waypoint x="{cx}.0" xsi:type="dc:Point" y="340.0"/></bpmndi:BPMNEdge>')
        parts.append(f'<bpmn2:participant id="Participant_R{n}" ifl:type="EndpointRecevier" name="{rcv["system"]}"><bpmn2:extensionElements>'
                     + prop('ifl:type', 'EndpointRecevier') + '</bpmn2:extensionElements></bpmn2:participant>')
        flows.append(f'<bpmn2:messageFlow id="MessageFlow_R{n}" name="{rcv["name"]}" sourceRef="{c["id"]}" targetRef="Participant_R{n}"><bpmn2:extensionElements>'
                     + props({**rcv['properties'], 'system': rcv['system']}) + '</bpmn2:extensionElements></bpmn2:messageFlow>')
    di.append('</bpmndi:BPMNPlane></bpmndi:BPMNDiagram>')

    return ('<?xml version="1.0" encoding="UTF-8"?>'
            '<bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" '
            'xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:di="http://www.omg.org/spec/DD/20100524/DI" xmlns:ifl="http:///com.sap.ifl.model/Ifl.xsd" '
            'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" id="Definitions_1">'
            '<bpmn2:collaboration id="Collaboration_1" name="Default Collaboration"><bpmn2:extensionElements>'
            + props({'namespaceMapping': '', 'allowedHeaderList': '', 'httpSessionHandling': flow.get('session', 'None'), 'ServerTrace': 'false',
                     'returnExceptionToSender': 'false', 'log': 'All events', 'componentVersion': '1.2',
                     'cmdVariantUri': 'ctype::IFlowVariant/cname::IFlowConfiguration/version::1.2.1'})
            + '</bpmn2:extensionElements>'
            + ''.join(parts) +
            '<bpmn2:participant id="Participant_Process_1" ifl:type="IntegrationProcess" name="Integration Process" processRef="Process_1"><bpmn2:extensionElements/></bpmn2:participant>'
            + ''.join(flows) +
            '</bpmn2:collaboration>'
            '<bpmn2:process id="Process_1" name="Integration Process"><bpmn2:extensionElements>'
            + props({'transactionTimeout': '30', 'componentVersion': '1.1',
                     'cmdVariantUri': 'ctype::FlowElementVariant/cname::IntegrationProcess/version::1.1.3',
                     'transactionalHandling': 'Not Required'})
            + '</bpmn2:extensionElements>' + ''.join(elems) + '</bpmn2:process>' + ''.join(di) + '</bpmn2:definitions>')


CONFIG_PROPS = [
    ('Source', '{{Source}}'), ('CustomsCountry', '{{Customs_Country}}'), ('ApiKeyAlias', '{{API_Key_Alias}}'),
    ('LookbackDays', '{{Lookback_Days}}'), ('RateType', '{{Rate_Type}}'), ('HomeCurrency', '{{Home_Currency}}'),
    ('Currencies', '{{Currencies}}'), ('Factors', '{{Factors}}'), ('TableRateType', '{{Table_Rate_Type}}'),
]

IDOC_RECEIVER = {
    'system': 'SAP_System', 'name': 'IDoc',
    'properties': {
        'cleanupHeaders': '1', 'Description': 'EXCHANGE_RATE01 to the SAP system through Cloud Connector', 'ComponentNS': 'sap',
        'privateKeyAlias': '', 'Name': 'IDoc', 'TransportProtocolVersion': '1.6.0', 'ComponentSWCVName': 'external',
        'proxyPort': '', 'IDocContentType': 'text/xml', 'CompressMessage': '0', 'sendHttpResponseCode': '0',
        'MessageProtocol': 'IDoc SOAP', 'ComponentSWCVId': '1.6.0', 'requestTimeout': '60000', 'direction': 'Receiver',
        'authentication': 'Basic', 'ComponentType': 'IDOC', 'address': '{{IDoc_Address}}', 'allowChunking': '1',
        'proxyType': 'sapcc', 'componentVersion': '1.6', 'proxyHost': '', 'system': 'SAP_System',
        'locationID': '{{IDoc_Location_ID}}', 'TransportProtocol': 'HTTP',
        'cmdVariantUri': 'ctype::AdapterVariant/cname::sap:IDOC/tp::HTTP/mp::IDoc SOAP/direction::Receiver/version::1.6.0',
        'credentialName': '{{IDoc_Credentials}}', 'MessageProtocolVersion': '1.6.0',
    },
}

IDOC_FLOW = {
    'symbolic': 'AllRatesToday_Official_Rates_to_SAP_IDoc',
    'name': 'AllRatesToday - Official Exchange Rates to SAP ECC and S4HANA (IDoc)',
    'description': 'Loads central bank, tax authority or customs exchange rates from the AllRatesToday API into TCURR as EXCHANGE_RATE01 IDocs through SAP Cloud Connector.',
    'capabilities': 'sap-IDOC;resolution:=optional',
    'scripts': ['FetchRates.groovy', 'ExchangeRateToIdoc.groovy'],
    'params': COMMON_PARAMS + IDOC_PARAMS,
    'refs': [
        ('SAP_System', 'ctype::AdapterVariant/cname::IDOC/tp::HTTP/mp::IDoc SOAP/direction::Receiver/version::1.6.0/attrId::address', 'Address', 'IDoc_Address'),
        ('SAP_System', 'ctype::AdapterVariant/cname::IDOC/tp::HTTP/mp::IDoc SOAP/direction::Receiver/version::1.6.0/attrId::locationID', 'Location ID', 'IDoc_Location_ID'),
        ('SAP_System', 'ctype::AdapterVariant/cname::IDOC/tp::HTTP/mp::IDoc SOAP/direction::Receiver/version::1.6.0/attrId::credentialName', 'Credential Name', 'IDoc_Credentials'),
    ],
    'steps': [
        {'id': 'StartEvent_1', 'kind': 'timer', 'name': 'Schedule'},
        {'id': 'CallActivity_1', 'kind': 'cm', 'name': 'Configuration',
         'properties': CONFIG_PROPS + [('SenderPort', '{{Sender_Port}}'), ('SenderPartner', '{{Sender_Partner}}'),
                                       ('ReceiverPort', '{{Receiver_Port}}'), ('ReceiverPartner', '{{Receiver_Partner}}')]},
        {'id': 'CallActivity_2', 'kind': 'script', 'name': 'Fetch rates', 'script': 'FetchRates.groovy'},
        {'id': 'CallActivity_3', 'kind': 'script', 'name': 'Map to EXCHANGE_RATE01', 'script': 'ExchangeRateToIdoc.groovy'},
        {'id': 'ServiceTask_1', 'kind': 'call', 'name': 'Send IDoc', 'receiver': IDOC_RECEIVER},
        {'id': 'EndEvent_1', 'kind': 'end', 'name': 'End'},
    ],
}


S4_PATH = '/sap/opu/odata4/sap/api_currencyexchangerate/srvd_a2x/sap/currencyexchangerate/0001/'


def http_receiver(name, address, method, auth, cred, key_alias='', req_headers='', send_body='false'):
    return {'system': name, 'name': 'HTTP', 'properties': {
        'Description': '', 'methodSourceExpression': '', 'ComponentNS': 'sap', 'privateKeyAlias': key_alias,
        'httpMethod': method, 'allowedResponseHeaders': '*', 'Name': 'HTTP', 'TransportProtocolVersion': '1.15.0',
        'ComponentSWCVName': 'external', 'proxyPort': '', 'enableMPLAttachments': 'true', 'httpAddressQuery': '',
        'httpRequestTimeout': '120000', 'MessageProtocol': 'None', 'ComponentSWCVId': '1.15.0',
        'allowedRequestHeaders': req_headers, 'direction': 'Receiver', 'ComponentType': 'HTTP',
        'httpShouldSendBody': send_body, 'throwExceptionOnFailure': 'true', 'proxyType': 'default',
        'componentVersion': '1.15', 'retryIteration': '1', 'proxyHost': '', 'retryOnConnectionFailure': 'false',
        'authenticationMethod': auth, 'locationID': '', 'retryInterval': '5', 'TransportProtocol': 'HTTP',
        'cmdVariantUri': 'ctype::AdapterVariant/cname::sap:HTTP/tp::HTTP/mp::None/direction::Receiver/version::1.15.0',
        'credentialName': cred, 'httpErrorResponseCodes': '', 'MessageProtocolVersion': '1.15.0',
        'httpAddressWithoutQuery': address}}


S4_PARAMS = [
    ('S4_Host', 'xsd:string', 'https://my000000-api.s4hana.cloud.sap', True, 'API host of the S/4HANA Cloud system (communication arrangement for SAP_COM_0B18), no trailing slash.'),
    ('S4_Authentication', 'xsd:string', 'Client Certificate', True, 'Client Certificate (X.509, as in SAP_COM_0B18) or Basic.'),
    ('S4_Private_Key_Alias', 'xsd:string', '', False, 'Keystore alias of the client certificate registered on the communication user (Client Certificate).'),
    ('S4_Credentials', 'xsd:string', '', False, 'User Credentials artifact for the communication user (Basic only).'),
]
S4_REFS = []

S4_FLOW = {
    'symbolic': 'AllRatesToday_Official_Rates_to_S4HANA_Cloud',
    'name': 'AllRatesToday - Official Exchange Rates to SAP S4HANA Cloud (Currency Exchange Rate API)',
    'description': 'Loads central bank, tax authority or customs exchange rates from the AllRatesToday API into SAP S/4HANA Cloud Public Edition through the Currency Exchange Rate OData V4 API (communication scenario SAP_COM_0B18).',
    'capabilities': 'sap-HTTP;resolution:=optional',
    'session': 'On Exchange',
    'scripts': ['FetchRates.groovy', 'ExchangeRateToS4Cloud.groovy'],
    'params': COMMON_PARAMS + S4_PARAMS,
    'refs': S4_REFS,
    'steps': [
        {'id': 'StartEvent_1', 'kind': 'timer', 'name': 'Schedule'},
        {'id': 'CallActivity_1', 'kind': 'cm', 'name': 'Configuration', 'properties': CONFIG_PROPS},
        {'id': 'CallActivity_2', 'kind': 'script', 'name': 'Fetch rates', 'script': 'FetchRates.groovy'},
        {'id': 'CallActivity_3', 'kind': 'script', 'name': 'Build batch', 'script': 'ExchangeRateToS4Cloud.groovy', 'function': 'processData'},
        {'id': 'ServiceTask_1', 'kind': 'call', 'name': 'Fetch CSRF token',
         'receiver': http_receiver('S4HANA_Cloud', '{{S4_Host}}' + S4_PATH, 'GET', '{{S4_Authentication}}', '{{S4_Credentials}}', '{{S4_Private_Key_Alias}}', 'x-csrf-token')},
        {'id': 'CallActivity_4', 'kind': 'script', 'name': 'Prepare batch', 'script': 'ExchangeRateToS4Cloud.groovy', 'function': 'prepareBatch'},
        {'id': 'ServiceTask_2', 'kind': 'call', 'name': 'Post rates',
         'receiver': http_receiver('S4HANA_Cloud_Batch', '{{S4_Host}}' + S4_PATH + '$batch', 'POST', '{{S4_Authentication}}', '{{S4_Credentials}}', '{{S4_Private_Key_Alias}}', 'x-csrf-token|Content-Type|Accept', 'true')},
        {'id': 'CallActivity_5', 'kind': 'script', 'name': 'Check results', 'script': 'ExchangeRateToS4Cloud.groovy', 'function': 'checkBatch'},
        {'id': 'EndEvent_1', 'kind': 'end', 'name': 'End'},
    ],
}

BYOR_PARAMS = [
    ('BYOR_Upload_URL', 'xsd:string', 'https://<uploadUrl from the service key>', True, 'uploadUrl from the SAP Market Rates Management service key.'),
    ('BYOR_OAuth_Credentials', 'xsd:string', 'MRM_BYOR_OAUTH', True, 'OAuth2 Client Credentials artifact (token URL, client ID and secret from the service key).'),
    ('BYOR_Provider', 'xsd:string', 'Y001', True, 'Data provider code (Y001 customer-managed, Y002 SAP-managed).'),
    ('BYOR_Property', 'xsd:string', 'CMID', True, 'Market data property, e.g. CMID (mid rate). Map it in Assign Currency Notations - Datafeed.'),
    ('BYOR_Max_Records', 'xsd:integer', '1500', True, 'Records per upload request: 1500 on the default plan, 100 on the free plan.'),
]

BYOR_FLOW = {
    'symbolic': 'AllRatesToday_Official_Rates_to_SAP_MRM_BYOR',
    'name': 'AllRatesToday - Official Exchange Rates to SAP Market Rates Management (BYOR)',
    'description': 'Uploads central bank, tax authority or customs exchange rates from the AllRatesToday API to SAP Market Rates Management, Bring Your Own Rates, for S/4HANA Cloud to pull.',
    'capabilities': 'sap-HTTP;resolution:=optional',
    'scripts': ['FetchRates.groovy', 'ExchangeRateToS4Cloud.groovy'],
    'params': COMMON_PARAMS + BYOR_PARAMS,
    'refs': [],
    'steps': [
        {'id': 'StartEvent_1', 'kind': 'timer', 'name': 'Schedule'},
        {'id': 'CallActivity_1', 'kind': 'cm', 'name': 'Configuration',
         'properties': CONFIG_PROPS + [('ByorProvider', '{{BYOR_Provider}}'), ('ByorProperty', '{{BYOR_Property}}'), ('ByorMaxRecords', '{{BYOR_Max_Records}}')]},
        {'id': 'CallActivity_2', 'kind': 'script', 'name': 'Fetch rates', 'script': 'FetchRates.groovy'},
        {'id': 'CallActivity_3', 'kind': 'script', 'name': 'Map to BYOR', 'script': 'ExchangeRateToS4Cloud.groovy', 'function': 'toByor'},
        {'id': 'ServiceTask_1', 'kind': 'call', 'name': 'Upload rates',
         'receiver': http_receiver('Market_Rates_Management', '{{BYOR_Upload_URL}}', 'POST', 'OAuth2 Client Credentials', '{{BYOR_OAuth_Credentials}}', '', 'Content-Type', 'true')},
        {'id': 'EndEvent_1', 'kind': 'end', 'name': 'End'},
    ],
}

FLOWS = [IDOC_FLOW, S4_FLOW, BYOR_FLOW]

# Package imports as generated by the Cloud Integration designer (copied from SAP's
# exchange-rate recipe manifest), plus the Secure Store API used by FetchRates.groovy.
IMPORT_PACKAGE = 'com.sap.esb.camel.mail.attachment.content.transfer.encoding, com.sap.esb.camel.core.lock.polling.strategy, com.sap.it.nm.concurrent, org.osgi.service.event, com.sap.esb.camel.security.cms.smime, com.sap.esb.camel.core.quartz, com.sap.esb.camel.ftp.sap.sftp.component, com.sap.it.nm.security, com.sap.it.rt.scc.connectivity, com.sap.esb.camel.ftp.proxy; resolution:="optional", com.sap.esb.camel.ftp.file.filter.glob, org.springframework.transaction.support, org.springframework.jdbc.datasource, com.sap.it.iflow.saxonee, net.sf.saxon.lib, com.sap.it.rt.csvtoxml.converter, org.mozilla.javascript, org.codehaus.groovy.reflection, org.codehaus.groovy.runtime.callsite, org.codehaus.groovy.runtime.typehandling, org.codehaus.groovy.runtime, groovy.lang, org.codehaus.groovy.jsr223, com.sap.gateway.ip.core.customdev.util, com.sap.gateway.ip.core.customdev.logging, com.sap.gateway.ip.core.customdev.processor, com.sap.it.api.mapping, com.sap.aii.mapping.value.api, com.sap.aii.mapping.lookup, com.sap.aii.mappingtool.tfapi, com.sap.aii.mappingtool.tf7.rt, com.sap.aii.mappingtool.tf7, com.sap.aii.mappingtool.tf3.rt, com.sap.aii.mappingtool.tf3, com.sap.aii.mappingtool.flib7, com.sap.aii.mappingtool.flib3, com.sap.aii.mapping.api, com.sap.aii.ib.bom.flib.types, com.sap.esb.datastore.wrapper, com.sap.esb.monitoring.cxf.response.log, com.sap.esb.monitoring.cxf.runtime.feature, com.sap.it.op.agent.trace.cxf, com.sap.esb.size.limiter, com.sap.esb.camel.webservice.endpoint.configurer, com.sap.esb.webservice.security.crypto.api, com.sap.esb.webservice.policy.alternative.selector, org.apache.cxf.ws.security.wss4j, org.apache.wss4j.common.crypto, javax.security.auth.callback, com.sap.it.iflow.model.runtime, com.sap.it.iflow.model, com.sap.sod.utils.idoc.soap.interceptors, com.sap.sod.utils.idoc.soap.processors, com.sap.sod.utils.idoc.soap, com.sap.sod.utils.soap.processors, com.sap.sod.utils.soap, com.sap.sod.utils.encoding.processors, com.sap.sod.utils.cxf.interceptors, com.sap.it.rt.scc.proxy, com.sap.esb.camel.jdbc.inprogress.repository, com.sap.esb.camel.jdbc.idempotency.repository, com.sap.esb.camel.webservice.cxfbinding, com.sap.esb.camel.mpl.access, com.sap.esb.camel.eip.splitter, com.sap.esb.camel.xmljson, com.sap.it.op.ed, com.sap.it.op.agent.ed.plugins.camel.api, com.sap.esb.webservice.authorization.supplier.api, org.apache.cxf.interceptor.security, net.sf.saxon.xpath, net.sf.saxon,com.sap.esb.application.services.cxf.interceptor,com.sap.esb.security,com.sap.it.op.agent.api,com.sap.it.op.agent.collector.camel,com.sap.it.op.agent.collector.cxf,com.sap.it.op.agent.mpl,javax.jms,javax.jws,javax.wsdl,javax.xml.bind.annotation,javax.xml.namespace,javax.xml.ws,org.apache.camel;version="2.8",org.apache.camel.builder;version="2.8",org.apache.camel.builder.xml;version="2.8",org.apache.camel.component.cxf,org.apache.camel.model;version="2.8",org.apache.camel.processor;version="2.8",org.apache.camel.processor.aggregate;version="2.8",org.apache.camel.spring.spi;version="2.8",org.apache.commons.logging,org.apache.cxf.binding,org.apache.cxf.binding.soap,org.apache.cxf.binding.soap.spring,org.apache.cxf.bus,org.apache.cxf.bus.resource,org.apache.cxf.bus.spring,org.apache.cxf.buslifecycle,org.apache.cxf.catalog,org.apache.cxf.configuration.jsse;version="2.5",org.apache.cxf.configuration.spring,org.apache.cxf.endpoint,org.apache.cxf.headers,org.apache.cxf.interceptor,org.apache.cxf.management.counters;version="2.5",org.apache.cxf.message,org.apache.cxf.phase,org.apache.cxf.resource,org.apache.cxf.service.factory,org.apache.cxf.service.model,org.apache.cxf.transport,org.apache.cxf.transport.common.gzip,org.apache.cxf.transport.http,org.apache.cxf.transport.http.policy,org.apache.cxf.workqueue,org.apache.cxf.ws.rm.persistence,org.apache.cxf.wsdl11,org.osgi.framework;version="1.6.0",org.slf4j;version="1.6",org.springframework.beans.factory.config;version="3.0",com.sap.esb.camel.security.cms,org.apache.camel.spi,com.sap.esb.webservice.audit.log,com.sap.esb.camel.endpoint.configurator.api,com.sap.esb.camel.jdbc.idempotency.reorg,javax.sql,org.apache.camel.processor.idempotent.jdbc,org.osgi.service.blueprint;version="[1.0.0,2.0.0)", com.sap.it.api, com.sap.it.api.securestore'


def manifest(f):
    lines = {
        'Manifest-Version': '1.0',
        'Bundle-ManifestVersion': '2',
        'Bundle-Name': f['name'],
        'Bundle-SymbolicName': f['symbolic'],
        'Bundle-Version': VERSION,
        'SAP-BundleType': 'IntegrationFlow',
        'SAP-NodeType': 'IFLMAP',
        'SAP-RuntimeProfile': 'iflmap',
        'WorkspaceProfile': 'iflmap',
        'Origin-Bundle-Name': f['name'],
        'Origin-Bundle-SymbolicName': f['symbolic'],
        'Origin-Bundle-Version': VERSION,
        'Import-Package': IMPORT_PACKAGE,
        'Require-Capability': f['capabilities'],
    }
    out = []
    for k, v in lines.items():  # wrap at 72 bytes, continuation lines start with a space
        line = f'{k}: {v}'
        out.append(line[:72])
        rest = line[72:]
        while rest:
            out.append(' ' + rest[:71])
            rest = rest[71:]
    return '\r\n'.join(out) + '\r\n\r\n'


def build_flow(f):
    src = os.path.join(DIST, 'src', f['symbolic'])
    shutil.rmtree(src, ignore_errors=True)
    res = os.path.join(src, 'src/main/resources')
    os.makedirs(os.path.join(res, 'scenarioflows/integrationflow'))
    os.makedirs(os.path.join(res, 'script'))
    os.makedirs(os.path.join(src, 'META-INF'))
    files = {
        'META-INF/MANIFEST.MF': manifest(f),
        'src/main/resources/metainfo.prop': f'#Store metainfo properties\ndescription={f["description"]}\n',
        'src/main/resources/parameters.prop': parameters_prop(f['params']),
        'src/main/resources/parameters.propdef': propdef(f['params'], f['refs']),
        f'src/main/resources/scenarioflows/integrationflow/{f["symbolic"]}.iflw': iflw(f),
    }
    for s in f['scripts']:
        files[f'src/main/resources/script/{s}'] = open(os.path.join(SCRIPTS, s)).read()
    for rel, content in files.items():
        with open(os.path.join(src, rel), 'w', newline='') as fh:
            fh.write(content)
    z = os.path.join(DIST, f'{f["symbolic"]}_v{VERSION}.zip')
    with zipfile.ZipFile(z, 'w', zipfile.ZIP_DEFLATED) as zf:
        zf.writestr('META-INF/MANIFEST.MF', files['META-INF/MANIFEST.MF'])  # manifest first
        for rel in sorted(files):
            if rel != 'META-INF/MANIFEST.MF':
                zf.writestr(rel, files[rel])
    return z


if __name__ == '__main__':
    os.makedirs(DIST, exist_ok=True)
    built = [build_flow(f) for f in FLOWS]
    bundle = os.path.join(DIST, f'AllRatesToday_SAP_Integration_Suite_v{VERSION}.zip')
    with zipfile.ZipFile(bundle, 'w', zipfile.ZIP_DEFLATED) as zf:
        for z in built:
            zf.write(z, os.path.basename(z))
        zf.write(os.path.join(ROOT, 'README.md'), 'README.md')
        zf.write(os.path.join(ROOT, 'LICENSE'), 'LICENSE')
    for z in built + [bundle]:
        print(os.path.relpath(z, ROOT), os.path.getsize(z), 'bytes')
