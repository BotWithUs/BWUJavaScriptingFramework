# Vendored: launcher protocol golden fixtures (automation surface)

Source: `BotWithUs/BotWithUs-Launcher`, directory `protocol/fixtures/`, at merge
`9d2df79f370cfe9f4a10c67d95e0b0b702347426` (PR #41, the native.zip amendment; launcher
ADR 0007, section 3.3).

**Only the automation-surface fixtures are vendored; the full surface stays in
the private launcher repository.** A host speaks only the automation pipe, so
the fixtures copied are those whose manifest `surface` includes `"automation"`:
70 of 128.

- Every `.msgpack` file is a byte-exact copy of the upstream file.
- `manifest.json` is **derived**: the upstream manifest (blob
  `0f5f1211eec2aa8e31c3ca0c9eff121a9162199d`) with only those entries kept, in upstream order,
  values unchanged. It is not byte-identical to upstream.

**Do not hand-edit.** A protocol change lands in the launcher first; this
directory is then re-vendored from a named launcher merge with the same rule,
and this file is rewritten with the new sha and blob ids.

Checks:

- `LauncherFixturesTest` (every build) recomputes the git blob id of each
  `.msgpack` below (SHA-1 over `"blob <length>\0" + bytes`) and fails if one
  differs, is missing, or is present but not listed, and if a file and a
  manifest entry do not pair up.
- `:core:launcherFixtureDriftTest` (opt-in, needs a launcher checkout) compares
  the files byte for byte with `git show <sha>:<path>`, and the manifest entries
  with the upstream automation-surface entries.

`.gitattributes` here marks everything `-text`, so no checkout converts a line ending.

## Blob ids at 9d2df79

```
7eef37e00caa5e1c13682c39eea19cad4160eae3  hello.req.msgpack
f4a32a676b045f6a7c0644c4ed005ec163023d3f  hello.resp.msgpack
4cec491a33a7011c2ca3b7475b946fd88dc38443  hello.req.host.msgpack
b682242c0b2e4028143e40299e8ce788716025dc  service.status.req.msgpack
c32e394c79e08c419acce475ea0c2f9075eee5d5  service.status.resp.msgpack
c758dff63198497914dfb166ca9e023fef16e216  auth.state.req.msgpack
aa04717d177e0a249dd732fc5a5b2e190a83b713  auth.state.resp.automation.msgpack
198e7b7e862664a4ea482921b4d522f3eac329e4  accounts.list.req.msgpack
5cf92c79ec22843aa90a0d35495df49ee42ec568  accounts.list.resp.automation.msgpack
27072f9c100579a5f664588cc84f6e4060466200  client.launch.req.msgpack
df069b8813f808dac8d92383b8014300bbdb2889  client.launch.resp.msgpack
cc552268c203881da192628d40aad387e7188946  client.stop.req.msgpack
3f6016d2ce9afc590447f9f60c36140ad9e529b3  client.stop.resp.msgpack
48d47ce4ed704bd1f9725d51dce7766c51377cb4  client.list.req.msgpack
72e56dcc03f98999424e691f8befff3254902042  client.list.resp.msgpack
1f51e0f6730680004e5029d25059883f5a674487  client.status.req.msgpack
f9fec7b13772263e7e7de2a83119f518dee4ce54  client.status.resp.msgpack
7ead1ade01b4ebcaa383829f592cb0fb87c6cb06  updates.status.req.msgpack
4f596edc54e407dba23c0f8766742055a428b2b4  updates.status.resp.msgpack
45e0dbc86b0bfde8a95f3fceec18167c2577dbcc  host.ack_close.req.msgpack
6d81394fbd2bfece925bdd622dab3181b9786c08  host.ack_close.resp.msgpack
8da2e4187cce4451b3e15c62e07fb37a8dc6aaed  events.subscribe.req.msgpack
ff2fe5129f595fb4e93ecd2d4960b61e698931f3  events.subscribe.resp.msgpack
ff0abd31921f4587a9c870f72705cbc3d099c854  client.started.event.msgpack
dd5b43fed4e8fb8b17057345783661979a25e432  client.state.event.msgpack
73ac9627b71eeb58c761ff2c0d186ae67877ecc7  client.state.event.failed.msgpack
429f0f14832b11186b77247cecce5cc8e52144bc  client.exited.event.msgpack
7839ec87a01ce1d54db408472696bc72e85178fe  agent.updated.event.msgpack
e017e1ff9a3f68f67b9988cdb2fd0ce899eeacbb  data.update_available.event.msgpack
2003e7eef8c103ef0d0de5e10b7d3a8eaa039530  data.update_applied.event.msgpack
c852941d457c5193dd7e7c0293a8ac4c826c985c  host.close_requested.event.msgpack
4ad4b2aed0f7dc7ec394e2c3388dc6014fc59e8d  licence.state.event.msgpack
e8ad92c40bd0496532fec37ba6bab9886ed29581  licence.state.event.link.msgpack
a57a2737b439330ab556ab94019ef127ed7ad203  service.shutting_down.event.msgpack
7971a360c21a20a07d536f59126b80bbea313c91  error.bad_frame.msgpack
7492625df218211ee5d75aaa3eccb31b366a04f6  error.frame_too_large.msgpack
bcae2253a55e027bc97f521520113d69d8c4a8d7  error.bad_envelope.msgpack
df555b5255b1da716b885ceb9ee4780804695a40  error.hello_required.msgpack
5e110a4f7e8f02e591859ee4bf4cd306820ef039  error.protocol_mismatch.msgpack
83162cdcb3f209456451c57dcc542d1f8d85ed5c  error.unknown_method.msgpack
3a1f822f51442b31e83156c299f45bdf1eea719e  error.not_permitted.msgpack
934519132909c8eb719deb9518a0d9c1d1e75088  error.caller_rejected.msgpack
1a058af7cf935127ddba97c10553a8baf0f7239e  error.host_already_connected.msgpack
dd37112c5f157f7fedb19d00b343fc6f630b0414  error.bad_request.msgpack
1e74628428d5c50dae2182b7acd1e92445240ec5  error.not_signed_in.msgpack
18b486922b33b88bbc9d0717ebdfcac26b140488  error.session_limit.msgpack
43cb721c7b17911c15576b17020c047cbed47ec2  error.concurrency_cap.msgpack
cb184c3e4038deaf61a53856e26e9f094ff69b83  error.rate_limited.msgpack
38925f986496505f2d46f3acb3b8115198d0cde3  error.account_not_found.msgpack
8e61fa5386c54cba5b107b6a6f649cb9adc90292  error.client_not_found.msgpack
e2a52950171ddae8810eb32ac564aaa49c69e662  error.agent_unavailable.msgpack
796e0a091d447f67a20f789e51c18aa2919e6b31  error.licence_unavailable.msgpack
9f384a2cf82478dfb5bf47172c06449ac90e0f6c  error.shutting_down.msgpack
f70960f361c036cdbadf60f3eabb42f783e13904  error.internal.msgpack
543880c5ba23cd727280a71e0ea8de813fa613d2  updates.status.resp.a4.msgpack
a97f81f2f18acac193d76fc8f5d80883a9a06d93  host.ack_close.req.later.msgpack
e1d43578e6d09f4c313f84ef51be7acf44466d93  events.subscribe.resp.data.msgpack
c3cc37e7bd3d451d343b36c42cb0613d7ff98811  data.update_available.event.checked.msgpack
9a7d24a8b334ce898e4ab3c0ae4595fb1da83fd3  data.update_available.event.error.msgpack
c17049cfb64940737449bb3e3bd5af9ad056af22  hello.resp.automation.msgpack
c32e394c79e08c419acce475ea0c2f9075eee5d5  service.status.resp.automation.msgpack
74ba2cf312294c1a03cdce3f889065af8bd9da6c  client.status.resp.automation.msgpack
dbe72c27c711674fe74f540b4078d021922e8e8b  events.subscribe.error.hosts_topic.msgpack
46569135f26d601f990534e4dc51837771653b96  client.launch.error.session_limit.msgpack
353b23c36b261119cc1c0548ea5c482d0374d316  native.update_available.event.msgpack
c402117718aa7d51cbf3a0c62d243212c2b032b6  native.update_available.event.error.msgpack
8c7172fd0957a8f29a5bf66b65969974e1f06e5f  native.update_applied.event.msgpack
ead75e38543e1805bbd36fd2791446d6d9ceb95e  events.subscribe.req.native.msgpack
d7c942280cff1f034b3c0b2938f43065eb4c3942  events.subscribe.resp.native.msgpack
340fc292d72cbaf5b4cd86b891f205993baa33a0  updates.status.resp.native.msgpack
```
