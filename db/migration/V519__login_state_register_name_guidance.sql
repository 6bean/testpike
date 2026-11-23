-- V519: carry argument-omission guidance in the built-in login-state register tool contract.
--
-- Root cause (screenshot-to-login-state journey probe, leg 1): the model found the
-- register contract through tool discovery but asked the user for the state name and
-- the tick selection instead of minting the draft card, because the schema
-- descriptions never said what to do when those arguments are omitted. Both values
-- are carried by the draft card itself, so the contract now instructs the model to
-- compose a concise name from the screenshot context and to select all parsed rows
-- by default, then call the tool in the same round.
--
-- Idempotent by construction: the WHERE clause only matches rows whose stored
-- description still differs from the new text, so replaying this migration is a
-- no-op. The same wording lives in the seed constant
-- LoginStateRegisterToolSeedInitializer.SCHEMA_LOGIN_STATE_REGISTER (guarded by
-- LoginStateRegisterToolSeedSchemaTest), and the dialog chain assembles tool
-- contracts from this table per request, so the update takes effect hot.
UPDATE tp_tool
SET input_schema = jsonb_set(
        jsonb_set(
            input_schema,
            '{properties,stateName,description}',
            to_jsonb('新登录态名称（必填，同一项目内重名将按续期规则覆盖旧值；用户未给出名称时，直接按截图中的站点或用途自行拟定一个简洁名称并继续调用，不要向用户追问名称）'::text)
        ),
        '{properties,entries,description}',
        to_jsonb('要登记的候选条目清单（只写名称与是否勾选；凭证值一律不要填写，填写也会被系统丢弃；用户未指明勾选哪些时，默认将解析出的候选行全选）'::text)
    ),
    updated_at = now()
WHERE exec_spec ->> 'intent' = 'session.login-state.register'
  AND (input_schema #>> '{properties,stateName,description}')
      IS DISTINCT FROM '新登录态名称（必填，同一项目内重名将按续期规则覆盖旧值；用户未给出名称时，直接按截图中的站点或用途自行拟定一个简洁名称并继续调用，不要向用户追问名称）';
