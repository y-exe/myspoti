/* SpotiFLAC's verified gateway protocol, isolated from Myspoti's download storage. */
var BASE = 'https://api.zarz.moe/v2';
var VERSIONS = { tidal: 'tidal-web@1.2.5', qobuz: 'qobuz-web@1.2.21' };
var CALLBACK = 'spotiflac://session-grant';
function randomId() { return myspoti.util.sha256(String(Date.now()) + ':' + Math.random()).slice(0, 32); }
function installId() {
  var id = myspoti.storage.get('installId');
  if (!id) { id = randomId(); myspoti.storage.set('installId', id); }
  return id;
}
// The SDK returns hex; the gateway signs with the unpadded base64url MAC as text.
function mac(key, text) {
  var hex = myspoti.util.hmacSha256(key, text), alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
  var out = '', bits = 0, value = 0;
  for (var i = 0; i < hex.length; i += 2) {
    value = (value << 8) | parseInt(hex.slice(i, i + 2), 16); bits += 8;
    while (bits >= 6) { bits -= 6; out += alphabet[(value >>> bits) & 63]; }
  }
  if (bits) out += alphabet[(value << (6 - bits)) & 63];
  return out;
}
function signature(session, version, method, path, body, now, nonce) {
  var time = new Date(now).toISOString(), hash = myspoti.util.sha256(body);
  var rolling = mac(session.session_secret, Math.floor(now / 300000) + ':' + session.session_id);
  var message = ['ZARZ-HMAC-V1', method, path, '', hash, time, nonce, session.session_id, version, 'extension'].join('\n');
  return { 'X-Zarz-Session': session.session_id, 'X-Zarz-Timestamp': time, 'X-Zarz-Nonce': nonce,
    'X-Zarz-Body-SHA256': hash, 'X-Zarz-Signature': mac(rolling, message),
    'X-Zarz-App-Version': version, 'X-Zarz-Platform': 'extension' };
}
function request(provider, method, path, data, signed, extra) {
  var body = data == null ? '' : JSON.stringify(data), version = VERSIONS[provider];
  var headers = signed ? signature(myspoti.storage.get(provider), version, method, '/v2' + path, body, Date.now(), randomId().slice(0, 24)) : {};
  headers.Accept = 'application/json'; headers['User-Agent'] = 'SpotiFLAC-Mobile/' + version;
  if (data != null) headers['Content-Type'] = 'application/json';
  for (var key in extra) headers[key] = extra[key];
  var response = myspoti.http.request({ url: BASE + path, method: method, headers: headers, body: body || undefined, timeout: 10000 });
  var result; try { result = response.json(); } catch (_) { result = {}; }
  if (!response.ok) throw Error(provider + ' HTTP ' + response.status + ' ' + (result.code || ''));
  return result;
}
function storeSession(provider, result) {
  var old = myspoti.storage.get(provider) || {};
  if (!result.session_id && !result.expires_at) return false;
  var session = { session_id: result.session_id || old.session_id, session_secret: result.session_secret || old.session_secret,
    expires_at: result.expires_at || old.expires_at };
  if (!session.session_id || !session.session_secret) return false;
  myspoti.storage.set(provider, session); return true;
}
function refresh(provider) {
  var session = myspoti.storage.get(provider);
  if (!session) return false;
  if (Date.parse(session.expires_at) - Date.now() > 3600000) return true;
  try { return storeSession(provider, request(provider, 'POST', '/session/refresh', { install_id: installId() }, true, {})); }
  catch (e) { myspoti.log(String(e)); return Date.parse(session.expires_at) > Date.now(); }
}
function verify(provider) {
  var result = request(provider, 'GET', '/bootstrap?' + myspoti.query({ app_version: VERSIONS[provider], install_id: installId() }), null, false, {});
  if (storeSession(provider, result)) return;
  var state = randomId(), callback = CALLBACK + '?cb_version=v2grant&state=' + state;
  var url = result.auth_url || result.challenge_url;
  if (url) { if (url.indexOf('state=') < 0) url += (url.indexOf('?') < 0 ? '?' : '&') + 'state=' + state; }
  else if (result.challenge_id) url = BASE + '/challenge?' + myspoti.query({ id: result.challenge_id, cb: callback });
  else throw Error('No verification page');
  var id = myspoti.auth.open({ url: url, callback: CALLBACK });
  myspoti.storage.set('auth', { id: id, provider: provider, state: state });
}
function queryValue(url, key) {
  var match = new RegExp('[?&#]' + key + '=([^&#]*)').exec(url);
  return match ? decodeURIComponent(match[1].replace(/\+/g, ' ')) : '';
}
myspoti.on('auth', function(e) {
  var auth = myspoti.storage.get('auth');
  if (!auth || auth.id !== e.id) return;
  myspoti.storage.remove('auth');
  if (e.error) { myspoti.log('Verification ' + e.error); return; }
  if (queryValue(e.url, 'state') !== auth.state) throw Error('Verification state mismatch');
  var grant = queryValue(e.url, 'grant') || queryValue(e.url, 'code');
  if (!grant) throw Error('No verification grant');
  if (!storeSession(auth.provider, request(auth.provider, 'POST', '/session/exchange', { grant: grant,
      install_id: installId(), app_version: VERSIONS[auth.provider], platform: 'extension' }, false, {}))) throw Error('No session');
});
myspoti.on('setting', function(e) { if (e.pressed && VERSIONS[e.key]) verify(e.key); });
function normalizedIsrc(value) { return String(value || '').replace(/[^a-z0-9]/gi, '').toUpperCase(); }
function recordingLengthMatches(seconds, durationMs) {
  return !durationMs || (seconds * 1000 >= durationMs - 5000 && seconds * 1000 <= durationMs + 15000);
}
function tidalId(isrc, durationMs) {
  var r = myspoti.http.get('https://tidal.com/v1/tracks?' + myspoti.query({ isrc: isrc, countryCode: 'US' }),
    { headers: { 'x-tidal-token': '49YxDN9a2aFV6RTG' }, timeout: 8000 });
  if (!r.ok) throw Error('Tidal catalog HTTP ' + r.status);
  var items = r.json().items || [];
  items = items.filter(function(t) { return normalizedIsrc(t.isrc) === normalizedIsrc(isrc) && t.streamReady !== false && recordingLengthMatches(t.duration, durationMs); });
  items.sort(function(a,b) { return (b.audioQuality === 'HI_RES_LOSSLESS' ? 2 : b.audioQuality === 'LOSSLESS' ? 1 : 0) -
    (a.audioQuality === 'HI_RES_LOSSLESS' ? 2 : a.audioQuality === 'LOSSLESS' ? 1 : 0); });
  return items.length ? String(items[0].id) : null;
}
function qobuzId(isrc, durationMs) {
  var time = String(Math.floor(Date.now() / 1000));
  var sig = myspoti.util.md5('tracksearchlimit1query' + isrc + time + '589be88e4538daea11f509d29e4a23b1');
  var r = myspoti.http.get('https://www.qobuz.com/api.json/0.2/track/search?' + myspoti.query({ query: isrc,
    limit: 1, app_id: '712109809', request_ts: time, request_sig: sig }), { timeout: 8000 });
  if (!r.ok) throw Error('Qobuz catalog HTTP ' + r.status);
  var items = (r.json().tracks || {}).items || [];
  for (var i = 0; i < items.length; i++) if (normalizedIsrc(items[i].isrc) === normalizedIsrc(isrc) && recordingLengthMatches(items[i].duration, durationMs)) return String(items[i].id);
  return null;
}
function ticket(provider, id) {
  var short = provider === 'tidal' ? 'tid' : 'qbz';
  var resource = provider === 'tidal' ? id : 'https://open.qobuz.com/track/' + id;
  var r = request(provider, 'POST', '/tickets', { capability: 'download_ticket', provider: short,
    resource_hash: myspoti.util.sha256(short + ':track:' + resource.toLowerCase()) }, true, {});
  if (!r.ticket_id && !r.ticket) throw Error('No download ticket');
  return r.ticket_id || r.ticket;
}
function tidalFile(data, small) {
  if (!data || String(data.assetPresentation).toUpperCase() === 'PREVIEW') return null;
  var file = { provider: 'Tidal', format: 'flac', bitDepth: data.bitDepth || 16, isrcMatched: true };
  if (data.manifest) {
    var decoded = myspoti.util.base64Decode(data.manifest);
    if (decoded.indexOf('<MPD') >= 0) {
      // Encrypted manifests are not accepted by this adapter.
      if (/ContentProtection/i.test(decoded)) return null;
      file.format = 'dash-flac'; file.manifest = data.manifest; file.bitDepth = data.bitDepth || 24; file.provider = file.bitDepth > 16 ? 'Tidal hi-res' : 'Tidal'; return file;
    }
    var manifest = JSON.parse(decoded);
    if (manifest.encryptionType && manifest.encryptionType !== 'NONE') return null;
    if (!manifest.urls || manifest.urls.length !== 1) return null;
    file.url = manifest.urls[0];
    if (small && /mp4/.test(manifest.mimeType || '')) { file.format = 'm4a'; return file; }
  } else file.url = data.url;
  return file.url && /\.flac(?:[?]|$)/i.test(file.url) ? file : null;
}
function download(provider, id, quality) {
  var headers = { 'X-Zarz-Ticket': ticket(provider, id) };
  if (provider === 'tidal') {
    var q = quality === 'small' ? 'LOW' : quality === 'hi-res' ? 'HI_RES_LOSSLESS' : 'LOSSLESS';
    return tidalFile(request(provider, 'POST', '/dl/tid', { id: id, quality: q }, true, headers).data, quality === 'small');
  }
  var r = request(provider, 'POST', '/dl/qbz', { id: id, type: 'track', url: 'https://open.qobuz.com/track/' + id,
    quality: quality === 'hi-res' ? 'hi-res-max' : 'cd', upload_to_r2: false }, true, headers);
  var data = r.data || {}, url = r.download_url || r.url || r.link || data.download_url || data.url || data.link;
  return url ? { url: url, format: 'flac', bitDepth: r.bit_depth || data.bit_depth || 16, provider: 'Qobuz', isrcMatched: true } : null;
}
myspoti.provide('download-source', function(track) {
  if (!normalizedIsrc(track.isrc)) { myspoti.log('No ISRC for this recording'); return []; }
  var providers = ['tidal', 'qobuz'];
  for (var i = 0; i < providers.length; i++) {
    var provider = providers[i];
    try {
      if (!refresh(provider)) continue;
      var id = provider === 'tidal' ? tidalId(track.isrc, track.durationMs) : qobuzId(track.isrc, track.durationMs);
      if (!id) { myspoti.log(provider + ': no exact ISRC match'); continue; }
      var file;
      try { file = download(provider, id, track.quality); }
      catch (e) { if (track.quality === 'cd') throw e; myspoti.log(String(e)); }
      if (!file && track.quality !== 'cd') file = download(provider, id, 'cd');
      if (file) return [file];
    } catch (e) { myspoti.log(String(e)); }
  }
  return [];
});
