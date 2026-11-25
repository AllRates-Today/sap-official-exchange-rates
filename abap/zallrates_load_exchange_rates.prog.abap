*&---------------------------------------------------------------------*
*& Report ZALLRATES_LOAD_EXCHANGE_RATES
*&---------------------------------------------------------------------*
*& Loads an official exchange-rate table from the AllRatesToday API into
*& TCURR with BAPI_EXCHRATE_CREATEMULTIPLE.
*&
*&  - Customs rates by clearance country:  /api/v1/customs/{country}/...
*&  - Central bank / tax authority table:  /api/v1/central-bank/{code}/...
*&
*& Every rate is written as foreign currency -> P_HOME (direct quotation)
*& with VALID_FROM = the table's own rate_date. Run it in test mode first.
*&
*& Prerequisites (see README.md):
*&  - SSL client PSE (STRUST) trusts allratestoday.com's certificate chain
*&  - the rate type exists, and translation ratios (OBBS) exist for every
*&    pair in that rate type; pass the same ratios in P_FACT (e.g. JPY:100)
*&  - the API key is passed in P_KEY or, better, read from secure storage
*&    in GET_API_KEY (replace that method for production use)
*&
*& Reference implementation: review it, run it in a test client, and adapt
*& error handling and authorisation checks to your standards.
*&---------------------------------------------------------------------*
REPORT zallrates_load_exchange_rates.

PARAMETERS:
  p_cntry TYPE land1,                         " clearance country (customs), e.g. NL
  p_src   TYPE char20 LOWER CASE,             " or source code, e.g. ecb, hmrc, zollde
  p_date  TYPE datum,                         " empty = table in force today
  p_home  TYPE waers OBLIGATORY,              " ledger / customs currency, e.g. EUR
  p_kurst TYPE kurst_curr OBLIGATORY DEFAULT 'ZCUS',
  p_curr  TYPE string LOWER CASE,             " optional list: USD,GBP,JPY
  p_fact  TYPE string LOWER CASE,             " optional ratios: JPY:100,KRW:1000
  p_rtyp  TYPE char20 LOWER CASE,             " table side for two-sided tables: import / export
  p_key   TYPE string LOWER CASE,             " API key (see GET_API_KEY)
  p_upd   AS CHECKBOX DEFAULT 'X',            " UPD_ALLOW
  p_test  AS CHECKBOX DEFAULT 'X'.            " list only, do not post

TYPES:
  BEGIN OF ty_rate,
    base  TYPE string,
    quote TYPE string,
    type  TYPE string,
    value TYPE decfloat34,
  END OF ty_rate,
  tt_rate TYPE STANDARD TABLE OF ty_rate WITH EMPTY KEY,
  BEGIN OF ty_table,
    bank      TYPE string,
    rate_date TYPE string,
    rates     TYPE tt_rate,
  END OF ty_table.

CLASS lcl_loader DEFINITION.
  PUBLIC SECTION.
    METHODS run.
  PRIVATE SECTION.
    METHODS get_api_key RETURNING VALUE(rv_key) TYPE string.
    METHODS build_url RETURNING VALUE(rv_url) TYPE string.
    METHODS fetch IMPORTING iv_url TYPE string RETURNING VALUE(rs_table) TYPE ty_table.
    METHODS factor_for IMPORTING iv_ccy TYPE string RETURNING VALUE(rv_factor) TYPE ffact_curr.
ENDCLASS.

CLASS lcl_loader IMPLEMENTATION.

  METHOD get_api_key.
    " Replace with your secure storage (e.g. SSF, a protected Z-table, or
    " the BTP destination service). Avoid keeping the key in a variant.
    rv_key = p_key.
  ENDMETHOD.

  METHOD build_url.
    DATA(lv_when) = COND string( WHEN p_date IS INITIAL THEN `latest`
                                 ELSE |{ p_date+0(4) }-{ p_date+4(2) }-{ p_date+6(2) }| ).
    IF p_cntry IS NOT INITIAL.
      rv_url = |https://allratestoday.com/api/v1/customs/{ to_upper( p_cntry ) }/{ lv_when }|.
    ELSEIF p_src IS NOT INITIAL.
      rv_url = |https://allratestoday.com/api/v1/central-bank/{ to_lower( p_src ) }/{ lv_when }|.
    ELSE.
      MESSAGE 'Enter a clearance country or a source code' TYPE 'E'.
    ENDIF.
  ENDMETHOD.

  METHOD fetch.
    DATA lo_http TYPE REF TO if_http_client.
    cl_http_client=>create_by_url( EXPORTING url = iv_url ssl_id = 'ANONYM'
                                   IMPORTING client = lo_http
                                   EXCEPTIONS OTHERS = 1 ).
    IF sy-subrc <> 0.
      MESSAGE |Cannot create HTTP client for { iv_url }| TYPE 'E'.
    ENDIF.
    lo_http->request->set_method( 'GET' ).
    lo_http->request->set_header_field( name = 'Authorization' value = |Bearer { get_api_key( ) }| ).
    lo_http->request->set_header_field( name = 'Accept' value = 'application/json' ).
    lo_http->send( EXCEPTIONS OTHERS = 1 ).
    IF sy-subrc = 0.
      lo_http->receive( EXCEPTIONS OTHERS = 1 ).
    ENDIF.
    IF sy-subrc <> 0.
      lo_http->get_last_error( IMPORTING message = DATA(lv_err) ).
      MESSAGE |HTTP error: { lv_err }| TYPE 'E'.
    ENDIF.
    lo_http->response->get_status( IMPORTING code = DATA(lv_code) ).
    DATA(lv_json) = lo_http->response->get_cdata( ).
    lo_http->close( ).
    IF lv_code <> 200.
      MESSAGE |AllRatesToday returned HTTP { lv_code }: { substring( val = lv_json len = nmin( val1 = 200 val2 = strlen( lv_json ) ) ) }| TYPE 'E'.
    ENDIF.
    /ui2/cl_json=>deserialize( EXPORTING json = lv_json CHANGING data = rs_table ).
  ENDMETHOD.

  METHOD factor_for.
    rv_factor = 1.
    SPLIT p_fact AT ',' INTO TABLE DATA(lt_pairs).
    LOOP AT lt_pairs INTO DATA(lv_pair).
      SPLIT lv_pair AT ':' INTO DATA(lv_c) DATA(lv_f).
      IF to_upper( condense( lv_c ) ) = iv_ccy AND lv_f CO ' 0123456789'.
        rv_factor = condense( lv_f ).
      ENDIF.
    ENDLOOP.
  ENDMETHOD.

  METHOD run.
    DATA: lt_list   TYPE STANDARD TABLE OF bapi1093_0,
          lt_return TYPE STANDARD TABLE OF bapiret2.

    DATA(ls_table) = fetch( build_url( ) ).
    IF ls_table-rates IS INITIAL.
      MESSAGE 'No rates in the response' TYPE 'E'.
    ENDIF.
    DATA(lv_valid) = CONV datum( replace( val = ls_table-rate_date sub = '-' with = '' occ = 0 ) ).
    DATA(lv_home) = CONV string( p_home ).
    DATA(lv_keep) = to_upper( p_curr ).

    " Two-sided tables carry several rows per currency (CBIC, Thai Customs: import
    " and export; URA: import, export, vat, income_tax). One rate type takes one
    " side: P_RTYP picks it, otherwise the API's own order applies (import first).
    IF p_rtyp IS NOT INITIAL.
      DELETE ls_table-rates WHERE type <> p_rtyp.
    ENDIF.
    TYPES: BEGIN OF ty_ranked,
             rank TYPE i,
             rate TYPE ty_rate,
           END OF ty_ranked.
    DATA lt_ranked TYPE STANDARD TABLE OF ty_ranked WITH EMPTY KEY.
    DATA lt_seen   TYPE SORTED TABLE OF string WITH UNIQUE KEY table_line.
    DATA(lt_pref) = VALUE string_table( ( `reference` ) ( `mid` ) ( `import` ) ( `buy` ) ( `sell` ) ( `export` ) ).
    LOOP AT ls_table-rates INTO DATA(ls_r).
      DATA(lv_rank) = 99.
      READ TABLE lt_pref WITH KEY table_line = ls_r-type TRANSPORTING NO FIELDS.
      IF sy-subrc = 0.
        lv_rank = sy-tabix.
      ENDIF.
      APPEND VALUE #( rank = lv_rank rate = ls_r ) TO lt_ranked.
    ENDLOOP.
    SORT lt_ranked BY rank STABLE.

    LOOP AT lt_ranked INTO DATA(ls_ranked).
      DATA(ls_rate) = ls_ranked-rate.
      DATA lv_foreign TYPE string.
      DATA lv_value   TYPE decfloat34.
      IF ls_rate-quote = lv_home.                       " already foreign -> home
        lv_foreign = ls_rate-base.  lv_value = ls_rate-value.
      ELSEIF ls_rate-base = lv_home AND ls_rate-value <> 0. " home -> foreign: invert
        lv_foreign = ls_rate-quote. lv_value = 1 / ls_rate-value.
      ELSE.
        CONTINUE.
      ENDIF.
      IF lv_keep IS NOT INITIAL AND lv_keep NS lv_foreign.
        CONTINUE.
      ENDIF.
      INSERT lv_foreign INTO TABLE lt_seen.             " first (best-ranked) row wins
      IF sy-subrc <> 0.
        CONTINUE.
      ENDIF.
      DATA(lv_factor) = factor_for( lv_foreign ).
      DATA(lv_rate) = round( val = lv_value * lv_factor dec = 5 ).
      IF lv_rate <= 0 OR lv_rate >= 10000.               " UKURSP is DEC 9,5
        WRITE: / |{ lv_foreign }: rate { lv_rate } does not fit; add a ratio in P_FACT/OBBS|.
        CONTINUE.
      ENDIF.
      APPEND VALUE #( rate_type   = p_kurst
                      from_curr   = lv_foreign
                      to_currncy  = p_home
                      valid_from  = lv_valid
                      exch_rate   = lv_rate
                      from_factor = lv_factor
                      to_factor   = 1 ) TO lt_list.
    ENDLOOP.

    WRITE: / |Source { ls_table-bank }, table dated { ls_table-rate_date }, { lines( lt_list ) } rate(s) for type { p_kurst }|.
    LOOP AT lt_list INTO DATA(ls_l).
      WRITE: / ls_l-from_factor, ls_l-from_curr, '->', ls_l-to_currncy, ls_l-exch_rate, ls_l-valid_from.
    ENDLOOP.
    IF p_test = abap_true OR lt_list IS INITIAL.
      WRITE: / 'Test mode: nothing posted.'.
      RETURN.
    ENDIF.

    CALL FUNCTION 'BAPI_EXCHRATE_CREATEMULTIPLE'
      EXPORTING
        upd_allow     = p_upd
        chg_fixed     = space
        dev_allow     = '000'
      TABLES
        exchrate_list = lt_list
        return        = lt_return.

    IF line_exists( lt_return[ type = 'E' ] ) OR line_exists( lt_return[ type = 'A' ] ).
      CALL FUNCTION 'BAPI_TRANSACTION_ROLLBACK'.
    ELSE.
      CALL FUNCTION 'BAPI_TRANSACTION_COMMIT' EXPORTING wait = abap_true.
    ENDIF.
    LOOP AT lt_return INTO DATA(ls_ret).
      WRITE: / ls_ret-type, ls_ret-id, ls_ret-number, ls_ret-message.
    ENDLOOP.
  ENDMETHOD.

ENDCLASS.

START-OF-SELECTION.
  NEW lcl_loader( )->run( ).
