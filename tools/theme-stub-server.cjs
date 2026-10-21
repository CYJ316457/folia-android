// A stub OpenAI-compatible endpoint for emulator verification of the AI theme flow.
//
// Returns a fixed dual theme with the quirks real providers produce, so the client's parsing and
// sanitising paths are all exercised: a fenced code block, a three-digit hex, a colour without a
// hash (which must fall back), and word colours whose entries include a blank word (dropped).
const http = require('http');

const RESPONSE = {
  light: {
    name: '晨雾微光',
    description: '醒来时窗外的雾还没散，你的名字先落在心上。',
    backgroundColor: '#F6EFE3',
    primaryColor: '#2B2118',
    accentColor: '#C2703A',
    secondaryColor: '#6F5B47',
    wordColors: [
      { word: '晨雾', color: '#C2703A' },
      { word: '', color: '#000000' },
      { word: '光', color: '#E8A33D' },
    ],
    lyricsIcons: ['Sunrise', 'CloudDrizzle'],
  },
  dark: {
    name: '夜航星図',
    description: '把耳机戴上，整座城市就沉进海里了。',
    backgroundColor: '#0B1622',
    primaryColor: '#E6EEF5',
    accentColor: '#5EC8D8',
    secondaryColor: 'not-a-colour',
    wordColors: [
      { word: '夜', color: '#5EC8D8' },
      { word: '海', color: '#7FA8E8' },
    ],
    lyricsIcons: ['Moon', 'Waves'],
  },
};

const server = http.createServer((req, res) => {
  let body = '';
  req.on('data', chunk => { body += chunk; });
  req.on('end', () => {
    console.log(`[stub] ${req.method} ${req.url} (${body.length} bytes)`);
    if (req.method !== 'POST') {
      res.writeHead(405).end('{}');
      return;
    }

    // Echo enough of the request that the log proves the prompt reached the endpoint.
    try {
      const parsed = JSON.parse(body);
      const messages = parsed.messages || [];
      console.log(`[stub] model=${parsed.model} messages=${messages.length}`);
      const user = messages.find(m => m.role === 'user');
      console.log('[stub] user prompt head:', String(user?.content || '').slice(0, 120).replace(/\n/g, ' | '));
      console.log('[stub] system prompt head:', String(messages[0]?.content || '').slice(0, 80));
    } catch {
      console.log('[stub] body was not JSON');
    }

    // Answer with prose and a fence, which is what several gateways actually return even when asked
    // for a bare JSON object.
    const content = 'Here is the theme:\n```json\n' + JSON.stringify(RESPONSE) + '\n```';
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      id: 'stub-1',
      choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }],
    }));
  });
});

server.listen(8099, '0.0.0.0', () => console.log('[stub] listening on 8099'));
