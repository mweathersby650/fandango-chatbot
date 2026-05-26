(function () {
  'use strict';

  const QUALITY_ORDER = ['SD', 'HD', 'HDX', '4K'];

  const css = `
    :host {
      --fab-size: 56px;
      --panel-width: 380px;
      --panel-height: 520px;
      --brand: #d4001a;
      --brand-dark: #a3001a;
      --bg: #ffffff;
      --surface: #f5f5f5;
      --text: #1a1a1a;
      --text-muted: #666;
      --bubble-user: #d4001a;
      --bubble-bot: #f0f0f0;
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
    }

    #fab {
      position: fixed;
      bottom: 24px;
      right: 24px;
      width: var(--fab-size);
      height: var(--fab-size);
      border-radius: 50%;
      background: var(--brand);
      color: #fff;
      border: none;
      cursor: pointer;
      box-shadow: 0 4px 12px rgba(0,0,0,0.3);
      display: flex;
      align-items: center;
      justify-content: center;
      z-index: 9999;
      transition: background 0.2s, transform 0.2s;
    }
    #fab:hover { background: var(--brand-dark); transform: scale(1.07); }
    #fab svg { width: 26px; height: 26px; }

    #panel {
      position: fixed;
      bottom: 90px;
      right: 24px;
      width: var(--panel-width);
      height: var(--panel-height);
      background: var(--bg);
      border-radius: 16px;
      box-shadow: 0 8px 32px rgba(0,0,0,0.2);
      display: flex;
      flex-direction: column;
      overflow: hidden;
      z-index: 9998;
      transform: translateY(20px);
      opacity: 0;
      pointer-events: none;
      transition: transform 0.25s ease, opacity 0.25s ease;
    }
    #panel.open {
      transform: translateY(0);
      opacity: 1;
      pointer-events: all;
    }

    #header {
      background: var(--brand);
      color: #fff;
      padding: 14px 16px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      flex-shrink: 0;
    }
    #header h2 { margin: 0; font-size: 15px; font-weight: 600; }
    #close-btn {
      background: none;
      border: none;
      color: #fff;
      cursor: pointer;
      padding: 4px;
      border-radius: 50%;
      display: flex;
      align-items: center;
    }
    #close-btn:hover { background: rgba(255,255,255,0.2); }

    #messages {
      flex: 1;
      overflow-y: auto;
      padding: 12px;
      display: flex;
      flex-direction: column;
      gap: 10px;
    }

    .bubble-wrap { display: flex; flex-direction: column; }
    .bubble-wrap.user { align-items: flex-end; }
    .bubble-wrap.bot { align-items: flex-start; }

    .bubble {
      max-width: 85%;
      padding: 10px 14px;
      border-radius: 16px;
      font-size: 14px;
      line-height: 1.45;
      word-break: break-word;
    }
    .bubble.user {
      background: var(--bubble-user);
      color: #fff;
      border-bottom-right-radius: 4px;
    }
    .bubble.bot {
      background: var(--bubble-bot);
      color: var(--text);
      border-bottom-left-radius: 4px;
    }

    .cards { display: flex; flex-direction: column; gap: 8px; margin-top: 6px; width: 100%; }

    .card {
      display: flex;
      gap: 10px;
      background: var(--surface);
      border-radius: 10px;
      overflow: hidden;
      text-decoration: none;
      color: var(--text);
      border: 1px solid #e0e0e0;
      transition: box-shadow 0.15s;
    }
    .card:hover { box-shadow: 0 2px 10px rgba(0,0,0,0.12); }

    .card-poster {
      width: 60px;
      min-width: 60px;
      height: 90px;
      object-fit: cover;
      background: #ccc;
    }
    .card-poster-placeholder {
      width: 60px;
      min-width: 60px;
      height: 90px;
      background: #ddd;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 22px;
    }

    .card-body {
      padding: 8px 10px 8px 0;
      flex: 1;
      display: flex;
      flex-direction: column;
      gap: 3px;
    }
    .card-title { font-weight: 600; font-size: 13px; line-height: 1.3; }
    .card-meta { font-size: 11px; color: var(--text-muted); }

    .badges { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 2px; }
    .badge {
      font-size: 10px;
      font-weight: 600;
      padding: 2px 6px;
      border-radius: 4px;
      background: #e0e0e0;
      color: #333;
    }
    .badge.quality { background: #1565c0; color: #fff; }
    .badge.rating { background: #e65100; color: #fff; }
    .badge.rt { background: #2e7d32; color: #fff; }

    .card-price { font-size: 12px; font-weight: 600; color: var(--brand); margin-top: auto; }

    #input-row {
      display: flex;
      gap: 8px;
      padding: 10px 12px;
      border-top: 1px solid #e0e0e0;
      flex-shrink: 0;
      background: #fff;
    }
    #input {
      flex: 1;
      border: 1px solid #ccc;
      border-radius: 20px;
      padding: 9px 14px;
      font-size: 14px;
      outline: none;
      transition: border-color 0.2s;
    }
    #input:focus { border-color: var(--brand); }
    #send-btn {
      background: var(--brand);
      color: #fff;
      border: none;
      border-radius: 50%;
      width: 38px;
      height: 38px;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
      transition: background 0.2s;
    }
    #send-btn:hover { background: var(--brand-dark); }
    #send-btn:disabled { background: #ccc; cursor: not-allowed; }
    #send-btn svg { width: 18px; height: 18px; }

    .typing { display: flex; gap: 4px; align-items: center; padding: 10px 14px; }
    .typing span {
      width: 7px; height: 7px; border-radius: 50%; background: #999;
      animation: bounce 1.2s infinite;
    }
    .typing span:nth-child(2) { animation-delay: 0.2s; }
    .typing span:nth-child(3) { animation-delay: 0.4s; }
    @keyframes bounce {
      0%, 60%, 100% { transform: translateY(0); }
      30% { transform: translateY(-6px); }
    }
  `;

  const filmIcon = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
    <rect x="2" y="2" width="20" height="20" rx="2.18" ry="2.18"/>
    <line x1="7" y1="2" x2="7" y2="22"/><line x1="17" y1="2" x2="17" y2="22"/>
    <line x1="2" y1="12" x2="22" y2="12"/><line x1="2" y1="7" x2="7" y2="7"/>
    <line x1="2" y1="17" x2="7" y2="17"/><line x1="17" y1="17" x2="22" y2="17"/>
    <line x1="17" y1="7" x2="22" y2="7"/>
  </svg>`;

  const closeIcon = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
    <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
  </svg>`;

  const sendIcon = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
    <line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/>
  </svg>`;

  class FandangoChatbot extends HTMLElement {
    constructor() {
      super();
      this._shadow = this.attachShadow({ mode: 'open' });
      this._sessionId = sessionStorage.getItem('fandango_chatbot_session') || null;
      this._open = false;
    }

    connectedCallback() {
      this._apiUrl = this.getAttribute('api-url') || 'http://localhost:8080';
      this._render();
      this._bindEvents();
      this._appendWelcome();
    }

    _render() {
      this._shadow.innerHTML = `
        <style>${css}</style>
        <button id="fab" aria-label="Open movie finder">${filmIcon}</button>
        <div id="panel" role="dialog" aria-modal="true" aria-label="Movie discovery chatbot">
          <div id="header">
            <h2>🎬 Find something to watch</h2>
            <button id="close-btn" aria-label="Close">${closeIcon}</button>
          </div>
          <div id="messages" role="log" aria-live="polite"></div>
          <div id="input-row">
            <input id="input" type="text" placeholder="e.g. scary movie under $4..." maxlength="500" autocomplete="off" />
            <button id="send-btn" aria-label="Send">${sendIcon}</button>
          </div>
        </div>
      `;
    }

    _bindEvents() {
      const fab = this._shadow.getElementById('fab');
      const panel = this._shadow.getElementById('panel');
      const closeBtn = this._shadow.getElementById('close-btn');
      const input = this._shadow.getElementById('input');
      const sendBtn = this._shadow.getElementById('send-btn');

      fab.addEventListener('click', () => this._togglePanel());
      closeBtn.addEventListener('click', () => this._closePanel());

      sendBtn.addEventListener('click', () => this._send());
      input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); this._send(); }
      });
    }

    _togglePanel() {
      this._open ? this._closePanel() : this._openPanel();
    }

    _openPanel() {
      this._open = true;
      this._shadow.getElementById('panel').classList.add('open');
      this._shadow.getElementById('input').focus();
    }

    _closePanel() {
      this._open = false;
      this._shadow.getElementById('panel').classList.remove('open');
    }

    _appendWelcome() {
      this._appendBotBubble(
        "Hi! I can help you find something great to watch on Fandango At Home. " +
        "Try asking: \"a funny movie for family night\" or \"sci-fi under $4\"."
      );
    }

    async _send() {
      const input = this._shadow.getElementById('input');
      const message = input.value.trim();
      if (!message) return;

      input.value = '';
      this._setLoading(true);
      this._appendUserBubble(message);
      const typing = this._appendTyping();

      try {
        const res = await fetch(`${this._apiUrl}/chat`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ sessionId: this._sessionId, message })
        });

        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();

        this._sessionId = data.sessionId;
        sessionStorage.setItem('fandango_chatbot_session', this._sessionId);

        typing.remove();
        this._appendBotBubble(data.reply, data.results || []);
        if (data.moreAvailable) {
          this._appendMoreButton();
        }
      } catch (err) {
        typing.remove();
        this._appendBotBubble("Sorry, something went wrong. Please try again in a moment.");
        console.error('[fandango-chatbot]', err);
      } finally {
        this._setLoading(false);
      }
    }

    _setLoading(loading) {
      this._shadow.getElementById('send-btn').disabled = loading;
      this._shadow.getElementById('input').disabled = loading;
    }

    _appendUserBubble(text) {
      const wrap = document.createElement('div');
      wrap.className = 'bubble-wrap user';
      wrap.innerHTML = `<div class="bubble user">${this._escape(text)}</div>`;
      this._messages().appendChild(wrap);
      this._scrollBottom();
    }

    _appendBotBubble(text, results = []) {
      const wrap = document.createElement('div');
      wrap.className = 'bubble-wrap bot';
      let html = `<div class="bubble bot">${this._escape(text)}</div>`;
      if (results.length > 0) {
        html += `<div class="cards">${results.map(this._renderCard.bind(this)).join('')}</div>`;
      }
      wrap.innerHTML = html;
      this._messages().appendChild(wrap);
      this._scrollBottom();
      return wrap;
    }

    _appendTyping() {
      const wrap = document.createElement('div');
      wrap.className = 'bubble-wrap bot';
      wrap.innerHTML = `<div class="bubble bot"><div class="typing"><span></span><span></span><span></span></div></div>`;
      this._messages().appendChild(wrap);
      this._scrollBottom();
      return wrap;
    }

    _appendMoreButton() {
      const wrap = document.createElement('div');
      wrap.className = 'bubble-wrap bot';
      const btn = document.createElement('button');
      btn.textContent = 'Show more results';
      btn.style.cssText = 'background:none;border:1px solid #d4001a;color:#d4001a;border-radius:16px;padding:6px 14px;cursor:pointer;font-size:13px;margin-top:4px;';
      btn.addEventListener('click', () => {
        wrap.remove();
        this._shadow.getElementById('input').value = 'show me more';
        this._send();
      });
      wrap.appendChild(btn);
      this._messages().appendChild(wrap);
      this._scrollBottom();
    }

    _renderCard(content) {
      const poster = content.posterUrl
        ? `<img class="card-poster" src="${this._escape(content.posterUrl)}" alt="${this._escape(content.title)}" loading="lazy" />`
        : `<div class="card-poster-placeholder">🎬</div>`;

      const genres = (content.genres || []).slice(0, 2).join(', ');
      const rating = content.mpaaRating ? `<span class="badge rating">${this._escape(content.mpaaRating)}</span>` : '';
      const rt = content.tomatoMeter != null ? `<span class="badge rt">🍅 ${content.tomatoMeter}%</span>` : '';

      const bestQuality = this._bestQuality(content.offers);
      const qualityBadge = bestQuality ? `<span class="badge quality">${bestQuality}</span>` : '';

      const bestOffer = this._bestOffer(content.offers);
      const priceStr = bestOffer
        ? `${bestOffer.offerType === 'rent' ? 'Rent' : 'Buy'} $${bestOffer.price.toFixed(2)}`
        : '';

      const href = content.deepLink || '#';

      return `
        <a class="card" href="${this._escape(href)}" target="_blank" rel="noopener noreferrer">
          ${poster}
          <div class="card-body">
            <div class="card-title">${this._escape(content.title)}</div>
            <div class="card-meta">${this._escape(genres)}</div>
            <div class="badges">${rating}${rt}${qualityBadge}</div>
            ${priceStr ? `<div class="card-price">${this._escape(priceStr)}</div>` : ''}
          </div>
        </a>
      `;
    }

    _bestQuality(offers) {
      if (!offers || !offers.length) return null;
      const qualities = offers.map(o => o.videoQuality).filter(Boolean);
      return qualities.sort((a, b) => QUALITY_ORDER.indexOf(b) - QUALITY_ORDER.indexOf(a))[0] || null;
    }

    _bestOffer(offers) {
      if (!offers || !offers.length) return null;
      const rents = offers.filter(o => o.offerType === 'rent' && o.price != null)
                          .sort((a, b) => a.price - b.price);
      return rents[0] || offers.find(o => o.price != null) || null;
    }

    _escape(str) {
      if (str == null) return '';
      return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
    }

    _messages() { return this._shadow.getElementById('messages'); }
    _scrollBottom() {
      const m = this._messages();
      m.scrollTop = m.scrollHeight;
    }
  }

  customElements.define('fandango-chatbot', FandangoChatbot);
})();
