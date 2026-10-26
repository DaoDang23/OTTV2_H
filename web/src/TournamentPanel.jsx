import { useEffect, useState } from 'react';

async function getJson(path) {
  const response = await fetch(path, {
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      'ngrok-skip-browser-warning': 'true',
    },
  });
  if (!response.ok) throw new Error(`${response.status} ${response.statusText}`);
  const contentType = response.headers.get('content-type') || '';
  if (!contentType.includes('application/json')) {
    throw new Error('Invalid JSON response from server');
  }
  return response.json();
}

export default function TournamentPanel() {
  const [tournaments, setTournaments] = useState([]);
  const [selectedId, setSelectedId] = useState('');
  const [view, setView] = useState(null);
  const [error, setError] = useState('');

  useEffect(() => {
    let live = true;
    getJson('/api/tournaments').then((items) => {
      if (!live) return;
      setTournaments(items);
      setSelectedId((current) => current || (items[0] ? String(items[0].id) : ''));
    }).catch((failure) => live && setError(failure.message));
    return () => { live = false; };
  }, []);

  useEffect(() => {
    if (!selectedId) { setView(null); return undefined; }
    let live = true;
    let socket;
    let reconnectTimer;
    const load = () => getJson(`/api/tournaments/${selectedId}`).then((result) => {
      if (live) setView(result);
    }).catch((failure) => live && setError(failure.message));
    const connect = () => {
      if (!live) return;
      const scheme = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      const backendWs = (import.meta.env?.VITE_BACKEND_URL || '').replace(/^http/, 'ws');
      const wsUrl = backendWs ? `${backendWs.replace(/\/$/, '')}/ws` : `${scheme}//${window.location.host}/ws`;
      socket = new WebSocket(wsUrl);
      socket.onopen = () => socket.send('CONNECT\naccept-version:1.2\nheart-beat:0,0\n\n\0');
      socket.onmessage = (message) => {
        const frames = String(message.data).split('\0');
        for (const frame of frames) {
          if (frame.startsWith('CONNECTED')) {
            socket.send(`SUBSCRIBE\nid:tournament-${selectedId}\ndestination:/topic/tournaments/${selectedId}\nack:auto\n\n\0`);
            load();
          } else if (frame.startsWith('MESSAGE')) {
            load();
          }
        }
      };
      socket.onclose = () => { if (live) reconnectTimer = window.setTimeout(connect, 3000); };
      socket.onerror = () => socket.close();
    };
    load();
    connect();
    const refreshTimer = window.setInterval(load, 15000);
    return () => {
      live = false;
      window.clearInterval(refreshTimer);
      window.clearTimeout(reconnectTimer);
      socket?.close();
    };
  }, [selectedId]);

  const rounds = Array.from({ length: 5 }, (_, index) => (view?.bracket ?? []).filter((node) => node.round === index + 1));
  return <section className="panel tournament-panel">
    <div className="section-title"><div><p className="eyebrow">TOURNAMENT</p><h2>Bracket giải đấu</h2><p className="muted">Tự cập nhật khi có kết quả trận.</p></div>
      <label>Giải<select value={selectedId} onChange={(event) => setSelectedId(event.target.value)}>
        <option value="">Chưa có giải</option>
        {tournaments.map((item) => <option key={item.id} value={item.id}>{item.name} · {item.status}</option>)}
      </select></label>
    </div>
    {error && <p className="error" role="alert">{error}</p>}
    {view ? <div className="bracket-scroll"><div className="bracket-grid">
      {rounds.map((nodes, index) => <div className="bracket-round" key={index}>
        <h3>{['Vòng 32', 'Vòng 16', 'Tứ kết', 'Bán kết', 'Chung kết'][index]}</h3>
        {nodes.map((node) => <article className="bracket-node" key={node.nodeId}>
          <span className="status">{node.status}</span>
          <strong>{node.botA || 'Chờ đội thắng'}</strong>
          <strong>{node.botB || 'Chờ đội thắng'}</strong>
          {node.winner && <p className="success">Thắng: {node.winner}</p>}
          {node.games.map((game) => <small key={game.gameNo}>Ván {game.gameNo}: {game.status}{game.winnerSide ? ` · bên ${game.winnerSide} thắng` : ''}</small>)}
        </article>)}
      </div>)}
    </div></div> : <p className="muted">Chưa có bracket để hiển thị.</p>}
  </section>;
}
