// A stub of the NetEase API's QR login endpoints.
//
// The timeline is driven by an HTTP call rather than a timer so the test can step it deterministically:
//   GET /login/qr/key        -> unikey
//   GET /login/qr/create    -> a real 1x1-ish PNG data URL
//   GET /login/qr/check     -> 801 until /__scan, 802 until /__confirm, then 803 + cookie
//   GET /__scan   /__confirm /__reset  -> advance the timeline
//
// /login/status and /user/account return the profile once the cookie is issued, mirroring the two
// endpoints the client cross-checks.
const http = require('http');

// A tiny valid PNG (8x8 white) so Coil has something real to decode.
const PNG_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAAOgAAADoCAAAAADAwvekAAACx0lEQVR4nO3dwaqkMBBA0evF///lns28jSCJUXse3DmbBi3TgTQUSSfl9qFBIiRCIiRCIiRCIiRCIiRCIiRCIiRCIvbjhW2xoc/J88frd+Ou9ic3ohIhERIhERIh1Tz6Y3a9d5TnrubBz0v9kQiJkAiJkAiJkHoeHeWlUb67Oq88m5+O4kbxuRGVCImQCImQCInYv/VFT6/bXiUREiEREiEREiER+9tfcMybZ/n0GP80iZAIiZAIiZAIidhHAavnYkbrsqP9Rk/3RyIkQiIkQiIkQup5dFts8CxPHuej3Pz/9CqJkAiJkAiJkAiJ2H5LHYazdd6nSIRESIRESIRESMQ+G3j3vOfq/VFend2/KxESIRESIRESIRHbap5icZ/Q56V56ij/SoRESIRESIRESDWPbl86l7K6T3c2n36qIyoREiEREiERErGvruMerx+fG51vmTVbj+FsH1NuRCVCIiRCIiRCIra766yr88hRfn1qfpobUYmQCImQCImQiO1q3drV+Nm6C7PrvVfzskRIhERIhERIhETso4DVugmrdYvu5mvqIyoREiEREiERUp+Pbn8/n9rPO7uO+1YdJImQCImQCImQCInY3lrXvdyRQft3378mERIhERIhERIhEfts4N38OVrfnb2+mmclQiIkQiIkQiKkmke3L50Hvbrvd7Q+PGpHIiRCIiRCIiRC6vPRz2QD28Vzp6t1A0dx//8frf10JUIiJEIiJGIfBcyeOxndH9XjPXs/6ag/s/lXIiRCIiRCIiRCIva3Gr773rVRO6PzNtkRlQiJkAiJkAiJ2J9ucHbeOXp+dR/vdhKfGVGJkAiJkAiJkIh9FLBaN3D2/mp+vDpPlQiJkAiJkAiJkPr7Xq5arZ8waueper4SIRESIRESIRESsb1VV+G3kQiJkAiJkAiJkAiJkAiJkAiJkAiJ8F934Fv+AIEifdxxiQz8AAAAAElFTkSuQmCC';

const COOKIE = 'MUSIC_U=stub-session-token; __csrf=stub';
const USER = { userId: 900001, nickname: 'StubListener', avatarUrl: 'http://example.com/a.png', vipType: 11 };

let phase = 'waiting'; // waiting -> scanned -> confirmed
let issued = false;

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const path = url.pathname;

  console.log(`[netease-stub] ${req.method} ${path}${url.search ? '?' + url.search.slice(1, 60) : ''} phase=${phase} issued=${issued}`);

  const json = (body) => {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(body));
  };

  if (path === '/__scan') { phase = 'scanned'; return json({ ok: true, phase }); }
  if (path === '/__confirm') { phase = 'confirmed'; issued = true; return json({ ok: true, phase }); }
  if (path === '/__reset') { phase = 'waiting'; issued = false; return json({ ok: true, phase }); }

  if (path === '/login/qr/key') {
    return json({ code: 200, data: { unikey: 'stub-unikey' } });
  }

  if (path === '/login/qr/create') {
    return json({ code: 200, data: { qrimg: `data:image/png;base64,${PNG_BASE64}` } });
  }

  if (path === '/login/qr/check') {
    if (issued) return json({ code: 803, cookie: COOKIE, message: 'authorized' });
    if (phase === 'scanned') return json({ code: 802, message: 'scanned, waiting for confirm' });
    return json({ code: 801, message: 'waiting for scan' });
  }

  if (path === '/login/status') {
    if (!issued) return json({ code: 301, message: 'not logged in', data: {} });
    if (!url.searchParams.get('cookie')) return json({ code: 301, message: 'no cookie', data: {} });
    return json({
      code: 200,
      cookie: COOKIE,
      data: { code: 200, profile: { userId: USER.userId, nickname: USER.nickname, avatarUrl: USER.avatarUrl, vipType: USER.vipType } },
    });
  }

  if (path === '/user/account') {
    if (!issued) return json({ code: 301, message: 'not logged in' });
    return json({ code: 200, account: { id: USER.userId }, profile: USER });
  }

  if (path === '/logout') {
    issued = false;
    phase = 'waiting';
    return json({ code: 200 });
  }

  if (path === '/register/anonimous') {
    return json({ code: 200, cookie: 'anonime=stub-anonymous' });
  }

  if (path === '/cloudsearch') {
    return json({ code: 200, result: { songs: [] } });
  }

  res.writeHead(404).end('{}');
});

server.listen(8097, '0.0.0.0', () => console.log('[netease-stub] listening on 8097'));
