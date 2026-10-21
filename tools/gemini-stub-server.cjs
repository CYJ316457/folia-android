// A stub Gemini generateContent endpoint.
//
// Verified separately from the OpenAI stub because the two wire formats differ in three places the
// client has to get right: the key goes in a query parameter, the prompt is split into
// systemInstruction/contents, and the answer arrives as candidates[0].content.parts[].text.
const http = require('http');

const THEME = {
  light: {
    name: '薄雾清晨',
    description: '醒来的时候，窗外的光像是谁轻轻放下的信。',
    backgroundColor: '#EFE9F7',
    primaryColor: '#241E33',
    accentColor: '#7C5CBF',
    secondaryColor: '#5B4F72',
    wordColors: [{ word: '雾', color: '#7C5CBF' }],
    lyricsIcons: ['Sunrise'],
  },
  dark: {
    name: '深海回音',
    description: '关掉灯之后，房间变成了另一片海。',
    backgroundColor: '#0D1B2A',
    primaryColor: '#DCE7F2',
    accentColor: '#6FA8DC',
    secondaryColor: '#8FA6BC',
    wordColors: [{ word: '海', color: '#6FA8DC' }],
    lyricsIcons: ['Waves'],
  },
};

const server = http.createServer((req, res) => {
  let body = '';
  req.on('data', chunk => { body += chunk; });
  req.on('end', () => {
    console.log(`[gemini-stub] ${req.method} ${req.url.split('?')[0]} key=${req.url.includes('key=') ? 'present' : 'MISSING'}`);

    if (req.method !== 'POST') {
      res.writeHead(405).end('{}');
      return;
    }

    try {
      const parsed = JSON.parse(body);
      const system = parsed.systemInstruction?.parts?.[0]?.text || '';
      const user = parsed.contents?.[0]?.parts?.[0]?.text || '';
      console.log(`[gemini-stub] schema=${Boolean(parsed.generationConfig?.responseSchema)} system=${system.length}b user=${user.slice(0, 60).replace(/\n/g, ' | ')}`);
    } catch {
      console.log('[gemini-stub] body was not JSON');
    }

    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      candidates: [{
        content: { role: 'model', parts: [{ text: JSON.stringify(THEME) }] },
        finishReason: 'STOP',
      }],
    }));
  });
});

server.listen(8098, '0.0.0.0', () => console.log('[gemini-stub] listening on 8098'));
