async page => {
  const input = __PAYLOAD__;
  const deadline = Date.now() + (input.operation === 'candidate_page' && Number.isInteger(input.readSeconds)
    ? Math.min(64, input.readSeconds) * 1000 : 64000);
  const budget = maximum => {
    const left = deadline - Date.now();
    if (left <= 0) throw Error('Action deadline exceeded');
    return Math.min(maximum, left);
  };
  const blocked = reason => ({ status: 'blocked', reason });
  const origin = 'https://www.offertoday.com';
  const legacyEntry = '/static/js/index.BKzheqN_.js';
  // Published DOM compatibility does not qualify the native send path. Only a reviewed
  // change to this source can promote the staged pair after its live receipt contract is verified.
  const publicEntry = '/static/js/index.D4zvvLnm.js';
  // No published build has a verified receipt source tied to the exact browser dispatch.
  // Registry entry only, not derived from task input or a runtime option. A qualified adapter
  // must gate the actual handler with permit(event, nativeRequest) and recheck the actual request
  // after any yield at the irreversible boundary. For CVs, pinResume reads the exact immutable revision
  // the native request must carry; acknowledgement and independent history must name that revision.
  // For text, acknowledgement must return the original inbound Sync_Message payload as bytes,
  // for the actual native clientMessageId. Not PUBACK, local Loading, or a cross-tab UI event.
  // A mutable ID or outer DOM capture listener is not proof. Tests install a synthetic source.
  const dispatchProofByBuild = { legacy: null, current: null };
  const build = tab => tab.evaluate(() => {
    const paths = [...document.querySelectorAll('script[type="module"][src]')].map(s => new URL(s.src, location.href))
      .filter(u => u.origin === location.origin && /^\/static\/js\/index\.[A-Za-z0-9_-]+\.js$/.test(u.pathname));
    return paths.length === 1 ? paths[0].pathname : '';
  });
  const jobPattern = /^\/hk\/job\/([A-Za-z0-9_-]{12,128}={0,2})\/?$/;
  const canonical = value => {
    const m = /^https:\/\/www\.offertoday\.com\/hk\/job\/([A-Za-z0-9_-]{12,128}={0,2})\/?(?:\?[^#]*)?$/.exec(value);
    return m ? `${origin}/hk/job/${m[1]}` : null;
  };
  const here = await page.evaluate(() => ({ protocol: location.protocol, hostname: location.hostname,
    username: location.username || new URL(location.href).username,
    password: new URL(location.href).password, port: location.port, pathname: location.pathname }));
  if (here.protocol !== 'https:' || here.hostname !== 'www.offertoday.com' ||
      here.username || here.password || here.port) return blocked('Current tab is not HTTPS OfferToday');
  const candidateRead = input.operation === 'candidate_page';
  let listBuild;
  if (candidateRead) {
    let source;
    try { source = await page.evaluate(value => { const u = new URL(value); return { origin: u.origin, username: u.username,
      password: u.password, hash: u.hash, pathname: u.pathname, href: u.href }; }, input.sourceUrl); }
    catch (_) { return blocked('Invalid read-only source'); }
    if (source.origin !== origin || source.username || source.password || source.hash ||
        !/^\/hk\/(?:recommend|search)[^/]*(?:\/|$)/.test(source.pathname) ||
        (input.readSeconds !== undefined && (!Number.isInteger(input.readSeconds) || input.readSeconds < 1 || input.readSeconds > 120)) ||
        !Number.isInteger(input.maxJobs) || input.maxJobs < 1 || input.maxJobs > 40 ||
        !/^(?:|0|[1-9][0-9]{0,2}|[a-f0-9]{64}:[1-9][0-9]{0,2})$/.test(input.cursor || ''))
      return blocked('Read-only source or limits are invalid');
    const unsaved = await page.evaluate(() => [...document.querySelectorAll('[contenteditable="true"], [role="dialog"], [aria-modal="true"]')]
      .some(el => el.getClientRects().length && (el.matches('[contenteditable="true"]') ? !!el.textContent.trim() : true)));
    if (unsaved) return blocked('Current page has a draft or dialog; read-only discovery will not discard it');
    if (page.url() !== source.href) {
      try { await page.goto(source.href, { waitUntil: 'domcontentloaded', timeout: budget(10000) }); }
      catch (_) { return blocked('Read-only list could not be loaded'); }
    }
    if (page.url() !== source.href) return blocked('Read-only list redirected to an unapproved source');
    listBuild = await build(page);
    if (listBuild !== publicEntry && (input.publicOnly || listBuild !== legacyEntry))
      return blocked('OfferToday read-only client version is not recognized');
    if (listBuild === publicEntry) {
      try { await page.locator('.jobCardTitle a[href*="/hk/job/"]').first().waitFor({ state: 'visible', timeout: budget(7500) }); }
      catch (_) { return blocked('OfferToday public job cards did not load'); }
    }
  }
  if (input.operation === 'discover' || candidateRead) {
    if (!candidateRead && !jobPattern.test(here.pathname) && !/^\/hk\/(?:recommend|search)[^/]*(?:\/|$)/i.test(here.pathname))
      return blocked('Open a HK job, recommendation or search page first');
    const observed = await page.evaluate(() => {
      const text = node => (node?.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 30000);
      const cards = [...document.querySelectorAll('.jobCardTitle a[href*="/hk/job/"]')].slice(0, 200).map(a => {
        let group = a.closest('.jobCardTitle');
        for (let i = 0; group && i < 5; i++, group = group.parentElement) {
          if (group.querySelectorAll('.jobCardTitle').length + Number(group.matches('.jobCardTitle')) !== 1) break;
          const companies = group.querySelectorAll('a[href*="/hk/company/"]');
          if (companies.length === 1) return { url: a.href, title: text(a), company: text(companies[0]), description: '' };
        }
        return { url: a.href, title: text(a), company: '', description: '' };
      });
      return { cards, detail: { title: text(document.querySelector('main h1')),
        company: text(document.querySelector('main [data-company],main .company,main a[href*="/hk/company/"]')),
        description: text(document.querySelector('main [data-description],main article p')) },
        search: !!document.querySelector('input[name="search_keyword"]') };
    });
    if (!candidateRead && jobPattern.test(here.pathname)) observed.cards.unshift({ url: page.url(), ...observed.detail });
    const jobs = [], seen = new Set();
    for (const card of observed.cards) {
      const url = canonical(card.url);
      if (!url || seen.has(url)) continue;
      seen.add(url);
      if (!card.title || !card.company) return blocked('Loaded job has incomplete public title/company');
      jobs.push({ url, title: card.title, company: card.company, description: card.description });
      if (jobs.length === (candidateRead ? 200 : 40)) break;
    }
    if (candidateRead) {
      const cursor = input.cursor || '';
      const current = listBuild === publicEntry;
      // Fresh anonymous visits can reorder the list. Never skip a positional offset on a different list.
      const fingerprint = current ? await page.evaluate(async ids => {
        const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(JSON.stringify(ids)));
        return Array.from(new Uint8Array(bytes), b => b.toString(16).padStart(2, '0')).join('');
      }, jobs.map(job => job.url)) : '';
      if (cursor && (current ? !cursor.startsWith(`${fingerprint}:`) : cursor.includes(':')))
        return blocked('OfferToday public job list changed; start a new read rather than skipping results');
      const offset = Number((current ? cursor.split(':')[1] : cursor) || 0);
      if (current && cursor && offset >= jobs.length) return blocked('OfferToday public read position is no longer valid');
      const selected = jobs.slice(offset, offset + input.maxJobs);
      if (!jobs.length && !observed.search) return blocked('No recognized read-only job list');
      const detailed = current ? await readPublicDetails(selected) : await page.evaluate(async ({ selected, origin }) => {
        const entry = await import('/static/js/index.BKzheqN_.js');
        if (typeof entry.I?.search?.get_getJobDetail !== 'function') return null;
        const records = [];
        const normalize = s => typeof s === 'string' ? s.trim().replace(/\s+/g, ' ') : '';
        for (const card of selected) {
          const jobId = card.url.split('/').pop();
          const job = await entry.I.search.get_getJobDetail({ encryptJobId: jobId });
          // Only the public detail reader is used: never chat state, account attachments or Chat/Apply.
          if (location.origin !== origin || job?.jobId !== jobId || normalize(job.jobName) !== card.title ||
              ![job.companyName, job.brandName].some(company => normalize(company) === card.company)) return null;
          const description = typeof job.jobDesc === 'string' ? job.jobDesc.replace(/<[^>]*>/g, ' ').trim() : '';
          // The published legacy detail page gates on numeric jobStatus too. Absence is not open.
          const available = job.isClosed === true || job.jobStatus === 3 ? false : job.jobStatus === 2 ? true : null;
          records.push({ ...card, description: description.slice(0, 30000),
            descriptionComplete: description.length >= 120 && description.length <= 30000, available });
        }
        return records;
      }, { selected, origin }).catch(() => null);
      if (!detailed) return blocked('OfferToday public detail Interface or identity is unrecognized');
      return { status: 'ok', jobs: detailed, nextUrl: '',
        cursor: offset + selected.length < jobs.length ? `${current ? `${fingerprint}:` : ''}${offset + selected.length}` : '',
        note: current
          ? 'Loaded public cards and verified visible details; gated previews and unrecognized details stay incomplete. No Chat/Apply, login, upload or send.'
          : 'Read-only loaded list and native public details; no Chat/Apply, login, upload or send. No guessed pagination beyond loaded cards.' };
    }
    return jobs.length || observed.search ? { status: 'ok', jobs } : blocked('No recognized loaded job or search view');
  }
  async function readPublicDetails(cards) {
    if (!cards.length) return [];
    let tab;
    const records = [];
    try {
      tab = await page.context().newPage();
      for (const card of cards) {
        if (Date.now() >= deadline) return null; // Keep the same source cursor on budget exhaustion.
        let detail;
        try {
          const response = await tab.goto(card.url, { waitUntil: 'domcontentloaded', timeout: budget(7500) });
          if (response?.ok() && canonical(tab.url()) === card.url && await build(tab) === publicEntry) {
            await tab.locator('h6').filter({ hasText: /^工作內容$/ }).first().waitFor({ state: 'visible', timeout: budget(6500) });
            detail = await tab.evaluate(({ card, origin, publicOnly }) => {
              const clean = s => s.trim().replace(/\s+/g, ' ');
              const visible = n => n && !!n.getClientRects().length && getComputedStyle(n).visibility !== 'hidden';
              const exact = (nodes, text) => [...nodes].some(n => visible(n) && clean(n.innerText) === text);
              const one = (selector, filter = visible) => {
                const nodes = [...document.querySelectorAll(selector)].filter(filter);
                return nodes.length === 1 ? nodes[0] : null;
              };
              const canonical = one('link[rel="canonical"]', () => true);
              const title = one('h1');
              const heading = one('h6', n => visible(n) && clean(n.innerText) === '工作內容');
              const header = title?.parentElement?.parentElement;
              const companies = header?.querySelectorAll(':scope > h6');
              const companyName = companies?.length === 1 && visible(companies[0]) ? clean(companies[0].innerText) : '';
              const descriptionNode = heading?.nextElementSibling;
              if (location.origin !== origin || location.pathname.replace(/\/$/, '') !== new URL(card.url).pathname ||
                  canonical?.href !== card.url || !visible(title) ||
                  clean(title.innerText) !== card.title || (companyName !== card.company && !companyName.startsWith(`${card.company}·`)) ||
                  header.parentElement !== heading?.parentElement?.parentElement || !visible(descriptionNode) || descriptionNode.tagName !== 'DIV') return null;
              const closed = exact(header.querySelectorAll('span'), '已關閉');
              const actions = header.nextElementSibling;
              const actionIs = label => visible(actions) && (clean(actions.innerText) === label ||
                exact(actions.querySelectorAll('button, [role="button"], div, span, p'), label));
              // Read labels only; either authenticated action can mutate state if clicked.
              const open = actionIs('傳送投遞消息') || (!publicOnly && actions?.id === 'jobDetailActionButton' &&
                (actionIs('傳送訊息') || actionIs('繼續溝通')));
              if (closed === open) return null;
              const visibleText = descriptionNode.innerText.trim();
              // The public view sometimes appends an explicit login prompt to a long preview.
              const loginGate = /(?:^|\n)[\t ]*登入查看完整職位描述$/u;
              const gated = loginGate.test(visibleText);
              const description = gated ? visibleText.replace(loginGate, '').trimEnd() : visibleText;
              return { description: description.length <= 30000 ? description : '',
                descriptionComplete: !gated && description.length >= 120 && description.length <= 30000, available: open };
            }, { card, origin, publicOnly: input.publicOnly === true });
          }
        } catch (_) { /* no retry or guessed detail */ }
        records.push({ ...card, ...(detail || { description: '', descriptionComplete: false, available: null }) });
      }
      return Date.now() < deadline ? records : null;
    } catch (_) { return null; }
    finally { if (tab) await tab.close(); }
  }
  if (!['preflight', 'send_text', 'send_resume'].includes(input.operation)) return blocked('Unrecognized browser operation');
  if (typeof input.jobId !== 'string' || !jobPattern.test(`/hk/job/${input.jobId}`) ||
      typeof input.title !== 'string' || !input.title.trim() || typeof input.company !== 'string' || !input.company.trim())
    return blocked('Approved job identity is incomplete');
  const sendUnavailable = () => blocked('OfferToday automatic sending is disabled: native CV uses an attachment ID without a verified immutable version or per-action receipt');
  // No preflight navigation is useful when neither production build can send.
  if (!dispatchProofByBuild.legacy && !dispatchProofByBuild.current) return sendUnavailable();
  const targetUrl = `${origin}/hk/job/${input.jobId}`;
  const unsaved = await page.evaluate(() => {
    const visible = el => !!(el.getClientRects().length && getComputedStyle(el).visibility !== 'hidden');
    return [...document.querySelectorAll('.js-message-input-editor[contenteditable="true"]')].some(el =>
      visible(el) && !!el.textContent.trim()) ||
      [...document.querySelectorAll('[role="dialog"], [aria-modal="true"], .ant-modal')].some(visible);
  });
  if (unsaved) return blocked('Current page has an open dialog or draft; native actions require manual review');
  if (canonical(page.url()) !== targetUrl) {
    if (input.operation !== 'preflight') return blocked('Approved page changed after claim; never restore or retry a send');
    try { await page.goto(targetUrl, { waitUntil: 'domcontentloaded', timeout: budget(10000) }); }
    catch (_) { return blocked('Approved job could not be loaded'); }
  }
  if (canonical(page.url()) !== targetUrl) return blocked('Approved job changed origin or identity');
  const actionBuild = await build(page);
  const staged = actionBuild === publicEntry;
  if (actionBuild !== legacyEntry && !staged) return blocked('OfferToday native client version is not recognized');
  const dispatchProofPath = staged ? dispatchProofByBuild.current : dispatchProofByBuild.legacy;
  if (!dispatchProofPath) return sendUnavailable();
  if (input.operation !== 'preflight' && input.preflight?.clientBuild !== actionBuild)
    return blocked('Native client build changed after preflight; never restore or retry a send');
  // Both published pairs retain the same export names and structurally corresponding bindings.
  // Never take module paths or aliases from task data or try either pair as a fallback.
  const langPath = staged ? '/static/js/lang-DMtzyqWP.js' : '/static/js/lang-73AtwcZ1.js';
  const digestPattern = /^[a-f0-9]{64}$/;
  const filename = input.operation === 'preflight' ? input.resumeFilename : input.preflight?.resumeFilename;
  const sha = input.operation === 'preflight' ? input.resumeSha256 : input.preflight?.resumeSha256;
  const size = input.operation === 'preflight' ? input.resumeSize : input.preflight?.resumeSize;
  if (typeof filename !== 'string' || !filename || !digestPattern.test(sha || '') ||
      !Number.isSafeInteger(size) || size <= 0 || size > 10 * 1024 * 1024)
    return blocked('Approved resume identity is incomplete');

  // This is the actual root Jotai store and the versioned entry's exported aliases.
  // All API calls below run in the page and return only bounded, non-sensitive metadata.
  const checkNative = async ({ phase, input, extra, targetUrl, filename, sha, size, deadline, actionBuild, langPath, staged, controls, dispatchProofPath }) => {
    const fail = () => ({ ok: false });
    const timeout = promise => {
      const left = Math.min(8000, deadline - Date.now());
      if (left <= 0) throw Error('Action deadline exceeded');
      return Promise.race([promise, new Promise((_, reject) => setTimeout(() => reject(Error('timeout')), left))]);
    };
    const id = value => typeof value === 'string' ?
      (value.length && value.length <= 256 && value === value.trim() ? value : null) :
      (Number.isSafeInteger(value) && value >= 0 ? String(value) : null);
    // Both published builds use chat.CPHUXtPm.proto. An int64 must be exact; their
    // native decoder can round large IDs, and stringifying the result cannot recover them.
    const positiveInt64 = value => {
      const decimal = typeof value === 'bigint' ? String(value) : id(value);
      return decimal && /^[1-9][0-9]{0,18}$/.test(decimal) &&
        (decimal.length < 19 || decimal <= '9223372036854775807') ? decimal : null;
    };
    const textSyncReceipt = (sentClientId, packet) => {
      if (!sentClientId || !(packet instanceof Uint8Array) || packet.length > 4160) return null;
      // Only the two protobuf messages used by a version-1 Sync_Message are supported.
      // Require known wire keys, valid lengths and no duplicates; never coerce int64 to Number.
      const fields = (bytes, keys) => {
        let offset = 0;
        const varint = () => {
          let value = 0n;
          for (let shift = 0n; shift <= 63n && offset < bytes.length; shift += 7n) {
            const byte = bytes[offset++];
            if (shift === 63n && byte > 1) break;
            value |= BigInt(byte & 127) << shift;
            if (byte < 128 && (byte !== 0 || shift === 0n)) return value;
            if (byte === 0) break;
          }
          throw Error('Invalid protobuf varint');
        };
        const out = new Map();
        while (offset < bytes.length) {
          const key = varint();
          if (!keys.includes(key) || out.has(key)) throw Error('Unexpected protobuf field');
          const value = varint();
          if ((key & 7n) === 2n) {
            if (value > BigInt(bytes.length - offset)) throw Error('Invalid protobuf length');
            const end = offset + Number(value);
            out.set(key, bytes.subarray(offset, end));
            offset = end;
          } else out.set(key, value);
        }
        return out;
      };
      try {
        // ChatProtocol: field 1 type, 2 version, 5 sync. SyncMessage: client ID, seq, server ID, extension.
        const envelope = fields(new Uint8Array(packet), [8n, 16n, 42n]);
        if (envelope.get(8n) !== 4n || envelope.get(16n) !== 1n || !envelope.has(42n)) return null;
        const sync = fields(envelope.get(42n), [8n, 16n, 24n, 34n]);
        if (positiveInt64(sync.get(8n)) !== sentClientId) return null;
        const rawExtension = sync.get(34n);
        if (rawExtension?.length) {
          if (rawExtension.length > 4096) return null;
          const extension = JSON.parse(new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(rawExtension));
          if (!extension || typeof extension !== 'object' || Array.isArray(extension) ||
              Object.keys(extension).some(key => key !== 'isMute' || extension[key] !== false)) return null;
        }
        const messageId = positiveInt64(sync.get(24n)), seq = positiveInt64(sync.get(16n));
        return messageId && (!sync.has(16n) || seq) ? { messageId, seq } : null;
      } catch (_) { return null; }
    };
    const time = value => {
      if (Number.isSafeInteger(value) && value >= 0) return value;
      if (typeof value === 'string' && /^(0|[1-9][0-9]*)$/.test(value)) {
        const parsed = Number(value);
        return Number.isSafeInteger(parsed) ? parsed : null;
      }
      return null;
    };
    const hash = async blob => {
      if (!(blob instanceof Blob) || blob.size !== size || blob.size > 10 * 1024 * 1024) return null;
      const digest = await crypto.subtle.digest('SHA-256', await blob.arrayBuffer());
      return [...new Uint8Array(digest)].map(b => b.toString(16).padStart(2, '0')).join('');
    };
    const sameResumeVersion = (a, b) => Boolean(a && b && typeof b.version === 'string' && id(b.version) === b.version &&
      a.resumeId === b.resumeId && a.originalOssKey === b.originalOssKey && a.version === b.version);
    try {
      if (location.origin !== 'https://www.offertoday.com' || location.href.split(/[?#]/)[0] !== targetUrl) return fail();
      const [entry, lang] = await timeout(Promise.all([import(actionBuild), import(langPath)]));
      const store = lang.s, api = entry.I;
      const preparesDispatch = phase === 'text_ready' || phase === 'resume_ready' || phase === 'save_ready';
      const resumeReceipt = phase === 'receipt' && extra.kind === 'resume';
      const dispatch = phase === 'preflight' || preparesDispatch || phase === 'upload_stage' || resumeReceipt
        ? await timeout(import(dispatchProofPath)) : null;
      if (dispatch && ['begin', 'pinResume', 'selectedResume', 'historyResumeRef', 'pendingUpload', 'guardNative']
        .some(name => typeof dispatch[name] !== 'function')) return fail();
      if (!store || typeof store.get !== 'function' || typeof store.set !== 'function' || typeof store.sub !== 'function' ||
          !entry.cs || typeof entry.cs.read !== 'function' || typeof entry.cs.write !== 'function' ||
          !entry.cI || !entry.aD || !entry.dc || !api ||
          typeof api.search?.get_getJobDetail !== 'function' ||
          typeof api.chat?.getHistoryMsgs !== 'function' ||
          typeof api.account?.getAttachList !== 'function' ||
          typeof api.account?.getDownloadAttach !== 'function') return fail();
      let owner = store.get(entry.aD)?.upsn;
      for (let attempt = 0; phase === 'preflight' && !owner && attempt < 40; attempt++) {
        await timeout(new Promise(resolve => setTimeout(resolve, 100)));
        owner = store.get(entry.aD)?.upsn;
      }
      if (typeof owner !== 'string' || !owner || owner.length > 256) return fail();
      const job = await timeout(api.search.get_getJobDetail({ encryptJobId: input.jobId }));
      const normalize = s => typeof s === 'string' ? s.trim().replace(/\s+/g, ' ') : '';
      // Both published job detail components use numeric jobStatus: only the observed open value qualifies.
      if (job?.jobId !== input.jobId || job.isClosed === true || job.jobStatus !== 2 ||
          normalize(job.jobName) !== normalize(input.title) ||
          ![job.companyName, job.brandName].some(s => normalize(s) === normalize(input.company)) ||
          typeof job.userId !== 'string' || !job.userId || job.userId.length > 256) return fail();
      const peer = job.userId;
      if (phase === 'preflight') {
        await timeout(store.set(entry.cs, peer)); // Native chat opener; never click Chat/Apply.
      }
      if (store.get(entry.cI) !== peer) return fail();
      const intact = () => Date.now() < deadline && location.href.split(/[?#]/)[0] === targetUrl &&
        store.get(entry.aD)?.upsn === owner && store.get(entry.cI) === peer;
      const sameBuild = () => {
        const entries = [...document.querySelectorAll('script[type="module"][src]')].map(s => new URL(s.src))
          .filter(u => u.origin === location.origin && /^\/static\/js\/index\.[A-Za-z0-9_-]+\.js$/.test(u.pathname));
        return entries.length === 1 && entries[0].pathname === actionBuild;
      };
      const usable = target => target?.isConnected && !target.matches(':disabled') &&
        !target.closest('[aria-disabled="true"], [inert]') && intact() && sameBuild();
      const approvedScope = request => request && request.jobId === input.jobId && request.owner === owner && request.peer === peer;
      const p = input.preflight;
      if (phase !== 'preflight' && (!p || p.jobId !== job.jobId || p.conversationId !== peer ||
          p.ownerUpsn !== owner || p.resumeFilename !== filename || p.resumeSha256 !== sha ||
          p.resumeSize !== size)) return fail();
      const history = async () => {
        const result = await timeout(api.chat.getHistoryMsgs({ friendId: peer, lastMessageId: -1, pageSize: 30 }));
        if (!intact() || !Array.isArray(result?.resultList) || result.resultList.length > 30 ||
            typeof result.hasMore !== 'boolean' || !Number.isSafeInteger(result.pageSize)) return null;
        const records = result.resultList;
        if (records.some(r => !id(r.messageId) || time(r.addTime) === null)) return null;
        const baselineIds = records.map(r => id(r.messageId));
        if (new Set(baselineIds).size !== baselineIds.length) return null;
        return { records, baselineIds, latestTime: Math.max(0, ...records.map(r => time(r.addTime))) };
      };
      const findContact = records => records.find(r => id(r.messageId) === input.contactMessageId &&
        r.fromSn === owner && r.toSn === peer && r.mediaType === 1 && [0, 1, 2].includes(r.status));
      const observed = await history();
      if (!observed) return fail();
      const { records, baselineIds, latestTime } = observed;
      const contact = findContact(records);
      if (phase !== 'preflight' && input.operation === 'send_resume' && !contact) return fail();
      if (phase !== 'preflight' && (!Array.isArray(p.baselineIds) ||
          p.baselineIds.some(v => typeof v !== 'string' || !id(v)) ||
          new Set(p.baselineIds).size !== p.baselineIds.length ||
          !Number.isSafeInteger(p.baselineTime) || latestTime < p.baselineTime ||
          (input.operation === 'send_text' && phase !== 'receipt' &&
            JSON.stringify(baselineIds) !== JSON.stringify(p.baselineIds)))) return fail();
      if (phase === 'receipt') {
        const baseline = extra.sendBaseline;
        if (!baseline || !id(baseline.dispatchMessageId)) return fail();
        // A history record only corroborates the ID independently acknowledged for this dispatch.
        const fresh = records.filter(r => id(r.messageId) === baseline.dispatchMessageId && !p.baselineIds.includes(id(r.messageId)) &&
          !baseline.ids.includes(id(r.messageId)) && id(r.messageId) !== input.contactMessageId &&
          r.fromSn === owner && r.toSn === peer && [0, 1, 2].includes(r.status));
        // Server sequence disambiguates equal timestamps; an unknown tie is not proof of a later send.
        const after = (r, markTime, markSeq) => markTime !== null && (time(r.addTime) > markTime ||
          (time(r.addTime) === markTime && Number.isSafeInteger(r.seq) &&
            Number.isSafeInteger(markSeq) && markSeq >= 0 && r.seq > markSeq));
        const matches = fresh.filter(r => after(r, baseline.time, baseline.seq) && (extra.kind === 'text' ?
          (r.mediaType === 1 && r.body?.text?.content === extra.message &&
            (!baseline.dispatchSeq || r.seq === undefined || positiveInt64(r.seq) === baseline.dispatchSeq) &&
            (r.clientMessageId === undefined || positiveInt64(r.clientMessageId) === baseline.clientMessageId)) :
          (r.mediaType === 6 && after(r, time(contact?.addTime), contact?.seq) && r.body?.compressiveCard?.type === 10 &&
            sameResumeVersion(dispatch.historyResumeRef(r), baseline.resume))));
        return matches.length === 1 ? { ok: true, messageId: id(matches[0].messageId) } : fail();
      }
      const list = await timeout(api.account.getAttachList());
      if (!intact() || !Array.isArray(list?.resume) || list.resume.length > 3) return fail();
      const candidates = list.resume.filter(a => a.fileName === filename);
      if (candidates.length > 1) return fail();
      let chosen = null;
      if (candidates.length === 1) {
        const a = candidates[0];
        if (typeof a.encryptAttachId !== 'string' || !a.encryptAttachId ||
            typeof a.originalOssKey !== 'string' || !a.originalOssKey ||
            a.produceStatus !== false || a.auditInfo?.auditStatus !== 1) return fail();
        chosen = { resumeId: a.encryptAttachId, originalOssKey: a.originalOssKey };
        if (['preflight', 'text_ready', 'resume_ready'].includes(phase)) {
          // Qualify any existing attachment before the first claim, not only after sending text.
          // Hash the exact dispatchable revision; a mutable-ID download may return different bytes.
          const pinned = await timeout(dispatch.pinResume({ ...chosen, jobId: job.jobId, peer, owner }));
          if (pinned?.resumeId !== chosen.resumeId || pinned.originalOssKey !== chosen.originalOssKey ||
              typeof pinned.version !== 'string' || id(pinned.version) !== pinned.version) return fail();
          const { version, blob } = pinned;
          const verifiedSha256 = await hash(blob);
          if (verifiedSha256 !== sha || !intact()) return fail();
          chosen = { ...chosen, version, verifiedSha256 };
        } else {
          const blob = await timeout(api.account.getDownloadAttach({ encryptAttachId: a.encryptAttachId },
            { responseType: 'blob', headers: { responseType: 'blob' } }));
          if (await hash(blob) !== sha || !intact()) return fail();
        }
      } else if (list.resume.length >= 3 || ['verify', 'resume_ready'].includes(phase)) return fail();
      if (phase === 'verify' || phase === 'resume_ready') {
        if (!chosen || chosen.resumeId !== extra.resumeId || chosen.originalOssKey !== extra.originalOssKey) return fail();
      }
      if (p?.resumeId && (phase === 'resume_ready' || phase === 'text_ready') &&
          !sameResumeVersion(chosen, { ...p, version: p.resumeVersion })) return fail();
      if (phase === 'upload_stage') {
        const target = controls?.target;
        const allowed = () => usable(target) && target.matches('input[type="file"]') &&
          Boolean(target.closest('[role="dialog"]')?.getClientRects().length);
        if (p.resumeId || chosen || !allowed() || target.files?.length !== 0) return fail();
        // Selecting a file normally auto-uploads. Suppress both events while staging; only relay
        // one change with immutable, checked bytes, in the same turn as the final identity check.
        let released = false, forwarded = false, verifiedEvent, stagedFile, frozenFile;
        const stillAuthorized = (event, request) => !released && event === verifiedEvent && allowed() &&
          approvedScope(request) && request.file === frozenFile && target.files?.length === 1 && target.files[0] === frozenFile;
        const unguard = dispatch.guardNative({ target, kind: 'upload', stillAuthorized, permit: (event, request) => {
          if (forwarded || !stillAuthorized(event, request)) return false;
          forwarded = true;
          return true;
        } });
        if (typeof unguard !== 'function') return fail();
        const capture = event => {
          if (event.target !== target || (event === verifiedEvent && allowed())) return;
          if (!stagedFile) stagedFile = target.files?.[0];
          event.preventDefault();
          event.stopImmediatePropagation();
        };
        const release = () => {
          if (released) return;
          released = true;
          clearTimeout(expiry);
          if (!forwarded && target.files?.[0] && [stagedFile, frozenFile].includes(target.files[0])) target.value = '';
          window.removeEventListener('input', capture, true);
          window.removeEventListener('change', capture, true);
          unguard();
        };
        const expiry = setTimeout(release, Math.max(0, deadline - Date.now()) + 10000);
        window.addEventListener('input', capture, true);
        window.addEventListener('change', capture, true);
        const commit = async () => {
          if (released || verifiedEvent || !allowed() || target.files?.length !== 1 ||
              !stagedFile || target.files[0] !== stagedFile || stagedFile.name !== filename || stagedFile.size !== size) return false;
          frozenFile = new File([await stagedFile.arrayBuffer()], filename,
            { type: stagedFile.type, lastModified: stagedFile.lastModified });
          if (await hash(frozenFile) !== sha || released || !allowed() || target.files[0] !== stagedFile) return false;
          const transfer = new DataTransfer();
          transfer.items.add(frozenFile);
          target.files = transfer.files;
          verifiedEvent = new Event('change', { bubbles: true });
          target.dispatchEvent(verifiedEvent);
          return forwarded;
        };
        return { arm: () => !released && allowed(), commit, release };
      }
      if (preparesDispatch) {
        // Each stage samples history after its last async material check.
        const latest = await history();
        if (!latest || latest.latestTime < latestTime) return fail();
        if (phase === 'text_ready' ? latest.latestTime !== latestTime ||
            JSON.stringify(latest.baselineIds) !== JSON.stringify(baselineIds) : !findContact(latest.records)) return fail();
        const seqs = [...records, ...latest.records].filter(r => time(r.addTime) === latest.latestTime).map(r => r.seq);
        const seq = seqs.length && seqs.every(n => Number.isSafeInteger(n) && n >= 0) ? Math.max(...seqs) : null;
        const ids = [...new Set([...baselineIds, ...latest.baselineIds])];
        const sendBaseline = { ids, time: latest.latestTime, seq };
        if (!controls) return fail();
        const { target, radio } = controls;
        const text = phase === 'text_ready', save = phase === 'save_ready', resume = phase === 'resume_ready';
        const eventTypes = text ? ['keydown'] : ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'];
        const matchesUpload = pending => pending && pending.originalOssKey === extra.originalOssKey &&
          pending.fileName === filename && pending.size === size && pending.sha === sha;
        const checked = () => {
          if (save) {
            const pending = dispatch.pendingUpload({ target, jobId: job.jobId, peer, owner });
            return !chosen && (target.getAttribute('aria-label') || target.innerText).trim() === '確認' &&
              Boolean(target.closest('[role="dialog"]')?.querySelector('input[type="file"]')) &&
              matchesUpload(pending);
          }
          if (text) {
            // Require unambiguous plain text. Layout can hide whitespace that a sender reading textContent would send.
            return document.activeElement === target && target.isContentEditable && target.childNodes.length === 1 &&
              target.firstChild.nodeType === Node.TEXT_NODE && target.textContent === input.message && target.innerText === input.message;
          }
          // Check the current native selection as well as the handler's exact request below.
          if (!sameResumeVersion(dispatch.selectedResume({ target, jobId: job.jobId, peer, owner }), chosen) ||
              (target.getAttribute('aria-label') || target.innerText).trim() !== '發送' ||
              !radio?.isConnected || !target.closest('[role="dialog"]')?.contains(radio) ||
              radio.getAttribute('data-testid') !== 'radio-checked-iconIcon') return false;
          for (let row = radio.parentElement, depth = 0; row && depth < 6; row = row.parentElement, depth++) {
            const indicators = row.querySelectorAll('svg[data-testid="radio-checked-iconIcon"],svg[data-testid="radio-unchecked-iconIcon"]');
            if (indicators.length > 1) return false;
            if (indicators.length === 1 && [...row.querySelectorAll('*')].some(el => !el.children.length && el.textContent.trim() === filename)) return true;
          }
          return false;
        };
        const authorized = () => usable(target) && checked();
        const { begin } = dispatch;
        let dispatched = false, denied = false, confirmation;
        const acceptable = event => event.isTrusted && event.composedPath().includes(target) &&
          !denied && !dispatched && authorized();
        // Window capture is an early filter, not authorization: intervening capture listeners can
        // change state. The qualified adapter calls permit at the native irreversible entry point.
        let permittedEvent, clientMessageId;
        const matchesRequest = request => approvedScope(request) && (text ? request.text === input.message &&
          positiveInt64(request.clientMessageId) !== null &&
          (!clientMessageId || positiveInt64(request.clientMessageId) === clientMessageId) :
          save ? matchesUpload(request.upload) : sameResumeVersion(request.resume, chosen));
        const unguard = dispatch.guardNative({ target, kind: text ? 'text' : save ? 'save' : 'resume',
          // The published text composer clears itself before the async transport runs.
          // At the boundary, recheck the exact packet and live scope, not the cleared draft.
          stillAuthorized: (event, request) => !denied && event === permittedEvent && matchesRequest(request) &&
            (text ? usable(target) : authorized()),
          permit: (event, request) => {
            try {
              if (matchesRequest(request) && acceptable(event)) {
                if (text) clientMessageId = positiveInt64(request.clientMessageId);
                if (!save) confirmation = begin({ event, request, kind: text ? 'text' : 'resume' });
                dispatched = save || (typeof confirmation?.acknowledgement === 'function' &&
                  typeof confirmation?.release === 'function' && matchesRequest(request) && authorized());
                if (dispatched) { permittedEvent = event; return true; }
              }
            } catch (_) { /* inability to recheck fails closed */ }
            denied = true;
            return false;
          }
        });
        if (typeof unguard !== 'function') return fail();
        const capture = event => {
          if (text && event.key !== 'Enter') return;
          try { if (acceptable(event)) return; } catch (_) { /* fail closed */ }
          denied = true;
          event.preventDefault();
          event.stopImmediatePropagation();
        };
        const release = () => {
          denied = true;
          clearTimeout(expiry);
          for (const type of eventTypes) window.removeEventListener(type, capture, true);
          try { unguard(); } finally { confirmation?.release?.(); }
        };
        // Normal cleanup is explicit; this lease also releases listeners if the owning command dies.
        const expiry = setTimeout(release, Math.max(0, deadline - Date.now()) + 10000);
        for (const type of eventTypes) window.addEventListener(type, capture, { capture: true, passive: false });
        const arm = () => {
          if (denied || dispatched || !authorized()) return false;
          if (text) return true;
          const rect = target.getBoundingClientRect();
          const x = (Math.max(0, rect.left) + Math.min(innerWidth, rect.right)) / 2;
          const y = (Math.max(0, rect.top) + Math.min(innerHeight, rect.bottom)) / 2;
          const hit = document.elementFromPoint(x, y);
          return Boolean(rect.width > 0 && rect.height > 0 && hit && target.contains(hit));
        };
        const proof = async () => {
          if (!dispatched || denied || !intact()) return null;
          if (save) return true; // A local save click is not a delivery acknowledgement.
          const ack = await timeout(confirmation.acknowledgement());
          const sync = text ? textSyncReceipt(clientMessageId, ack) : null;
          const dispatchMessageId = text ? sync?.messageId : id(ack?.messageId);
          if (!dispatchMessageId || ids.includes(dispatchMessageId) || !intact() || denied ||
              (resume && !sameResumeVersion(ack.resume, chosen))) return null;
          return { ...sendBaseline, dispatchMessageId, ...(text
            ? { clientMessageId, dispatchSeq: sync.seq } : { resume: chosen }) };
        };
        return { arm, proof, release };
      }
      if (phase === 'preflight') return { ok: true, clientBuild: actionBuild, jobId: job.jobId, conversationId: peer,
        ownerUpsn: owner, baselineIds, baselineTime: latestTime,
        resumeFilename: filename, resumeSha256: sha, resumeSize: size,
        resumeId: chosen?.resumeId || null, originalOssKey: chosen?.originalOssKey || null,
        resumeVersion: chosen?.version || null, attachmentCount: list.resume.length };
      return { ok: true, resumeId: chosen?.resumeId || null,
        originalOssKey: chosen?.originalOssKey || null, attachmentCount: list.resume.length };
    } catch (_) { return fail(); }
  };
  const native = (phase, extra = {}, controls = null) => {
    const args = { phase, input, extra, targetUrl, filename, sha, size, deadline, actionBuild, langPath, staged, controls, dispatchProofPath };
    return controls ? page.evaluateHandle(checkNative, args) : page.evaluate(checkNative, args);
  };
  const guardedSend = async (phase, control, extra = {}, selected = null) => {
    const target = await control.elementHandle({ timeout: budget(1000) });
    let radio, guard;
    try {
      const text = phase === 'text_ready';
      // Actionability may wait, so complete the non-sending trial before sampling history.
      if (!text) await target.click({ trial: true, timeout: budget(4000) });
      radio = selected ? await selected.elementHandle({ timeout: budget(1000) }) : null;
      guard = await native(phase, extra, { target, radio });
      if (!await guard.evaluate(g => g.arm?.() === true)) return null;
      if (text) await target.press('Enter', { timeout: budget(1000) });
      // Skip the second Playwright actionability wait, not validation: the trial, fresh
      // history, renderer hit-test and dispatch guard already gate this exact element.
      else await target.click({ force: true, scroll: 'none', noWaitAfter: true, timeout: budget(1000) });
      return await guard.evaluate(g => g.proof());
    } finally {
      if (guard) {
        await guard.evaluate(g => g.release?.()).catch(() => {});
        await guard.dispose().catch(() => {});
      }
      if (radio) await radio.dispose().catch(() => {});
      await target.dispose().catch(() => {});
    }
  };
  const guardedUpload = async file => {
    const target = await file.elementHandle({ timeout: budget(1000) });
    let guard;
    try {
      guard = await native('upload_stage', {}, { target });
      if (!await guard.evaluate(g => g.arm?.() === true)) return null;
      await target.setInputFiles(input.resumePath, { timeout: budget(5000) });
      const response = page.waitForResponse(r =>
        r.url().split('?')[0] === `${origin}/wapi/geek/mine/attach/upload` && r.request().method() === 'POST' &&
        r.frame() === page.mainFrame(), { timeout: budget(12000) }).catch(() => null);
      if (!await guard.evaluate(g => g.commit())) return null;
      return await response;
    } finally {
      if (guard) {
        await guard.evaluate(g => g.release?.()).catch(() => {});
        await guard.dispose().catch(() => {});
      }
      await target.dispose().catch(() => {});
    }
  };
  const editor = page.locator('.js-message-input-editor[contenteditable="true"]');
  const opener = editor.locator('xpath=..').locator('button[aria-label="open-resume"]:has(svg[data-testid="resume-icon-2Icon"])');
  const visibleOne = async locator => await locator.count() === 1 && await locator.isVisible();
  const picker = page.getByRole('dialog').filter({ has: page.getByRole('button', { name: '發送', exact: true }) });
  const uploadDialog = page.getByRole('dialog').filter({ has: page.locator('input[type="file"][accept*=".pdf"]') });
  const radios = picker.locator('svg[data-testid="radio-checked-iconIcon"],svg[data-testid="radio-unchecked-iconIcon"]');
  const resumeRadio = async () => radios.evaluateAll((nodes, name) => {
    const matches = nodes.flatMap((svg, index) => {
      let row = svg.parentElement;
      for (let depth = 0; row && depth < 6; depth++, row = row.parentElement) {
        if (row.querySelectorAll('svg[data-testid="radio-checked-iconIcon"],svg[data-testid="radio-unchecked-iconIcon"]').length === 1 &&
            [...row.querySelectorAll('*')].some(el => !el.children.length && el.textContent.trim() === name)) return [index];
      }
      return [];
    });
    return matches.length === 1 ? matches[0] : -1;
  }, filename);
  const openResumeFlow = async () => {
    if (!await visibleOne(opener)) return false;
    await opener.click({ timeout: budget(4000) });
    await picker.or(uploadDialog).first().waitFor({ state: 'visible', timeout: budget(6000) });
    return await visibleOne(picker) || await visibleOne(uploadDialog);
  };
  try {
    if (input.operation === 'preflight') {
      if (await page.locator('[role="dialog"]:visible').count())
        return blocked('Existing dialog needs manual review before preflight');
      const state = await native('preflight');
      if (state.ok) await editor.waitFor({ state: 'visible', timeout: budget(6000) });
      if (!state.ok || !await visibleOne(editor) || !await editor.isEditable() ||
          (await editor.innerText()).trim() !== '' || !await openResumeFlow())
        return blocked('Native job, account, editor or approved resume is not safely recognized');
      if (state.resumeId) {
        if (await resumeRadio() < 0) return blocked('Approved resume is not present in native picker');
      } else if (state.attachmentCount > 0 && !await visibleOne(picker.getByText('上傳新履歷', { exact: true })))
        return blocked('Approved new-resume upload is unavailable');
      // Close the read-only picker before claim; never leave a modal above the editor.
      for (let i = 0; i < 2 && (await visibleOne(picker) || await visibleOne(uploadDialog)); i++) {
        budget(4000);
        await page.keyboard.press('Escape');
      }
      if (await visibleOne(picker) || await visibleOne(uploadDialog) || await page.locator('[role="dialog"]:visible').count())
        return blocked('Native picker could not be safely closed before claim');
      return { status: 'ok', ...state, ok: undefined };
    }
    const p = input.preflight;
    if (!p || !Array.isArray(p.baselineIds) || p.baselineIds.length > 30 ||
        canonical(page.url()) !== targetUrl) return blocked('Approved preflight changed');
    if (input.operation === 'send_text') {
      const approvedText = typeof input.message === 'string' && await page.evaluate(async ({ message, sha }) => {
        const bytes = new TextEncoder().encode(message);
        const digest = await crypto.subtle.digest('SHA-256', bytes);
        return [...new Uint8Array(digest)].map(b => b.toString(16).padStart(2, '0')).join('') === sha;
      }, { message: input.message, sha: input.messageSha256 });
      if (!approvedText || typeof input.message !== 'string' || !input.message.trim() ||
          !digestPattern.test(input.messageSha256 || '') || !await visibleOne(editor) ||
          !await editor.isEditable() || (await editor.innerText()).trim() !== '' ||
          (p.resumeId && !(await native('verify', { baselineIds: p.baselineIds, resumeId: p.resumeId,
            originalOssKey: p.originalOssKey })).ok))
        return blocked('Text, editor or approved identity changed before send');
      const before = await native('guard', { baselineIds: p.baselineIds });
      if (!before.ok || (p.resumeId && (before.resumeId !== p.resumeId || before.originalOssKey !== p.originalOssKey)))
        return blocked('Native identity or resume changed before text send');
      await editor.fill(input.message, { timeout: budget(4000) });
      const sendBaseline = await guardedSend('text_ready', editor);
      if (!sendBaseline) return blocked('Text dispatch or acknowledgement could not be verified; never retry');
      for (let i = 0; i < 8 && Date.now() < deadline - 9000; i++) {
        const receipt = await native('receipt', { kind: 'text', message: input.message, sendBaseline });
        if (receipt.ok) return { status: 'ok', contact: { jobId: input.jobId, conversationId: p.conversationId,
          messageId: receipt.messageId, initialMessageSha256: input.messageSha256,
          proof: `application sync for native text and fresh server history ${receipt.messageId} peer ${p.conversationId}` } };
        await page.waitForTimeout(500);
      }
      return blocked('Fresh server history text receipt unavailable; never retry');
    }
    if (typeof input.contactMessageId !== 'string' || !input.contactMessageId ||
        input.resumeFilename !== filename || input.resumeSha256 !== sha || input.resumeSize !== size ||
        !await visibleOne(editor) || (await editor.innerText()).trim() !== '' ||
        !(await native('guard', { baselineIds: p.baselineIds })).ok)
      return blocked('Contact or native peer changed before resume send');
    let chosen = { resumeId: p.resumeId, originalOssKey: p.originalOssKey };
    if (!chosen.resumeId) {
      if (p.attachmentCount >= 3) return blocked('Resume capacity changed');
      if (!await visibleOne(picker) && !await visibleOne(uploadDialog) && !await openResumeFlow()) return blocked('Native resume picker unavailable');
      if (p.attachmentCount) {
        const newRow = picker.getByText('上傳新履歷', { exact: true });
        if (!await visibleOne(newRow)) return blocked('New resume upload unavailable');
        await newRow.click({ timeout: budget(4000) });
      }
      const upload = uploadDialog;
      await upload.waitFor({ state: 'visible', timeout: budget(6000) });
      if (!await visibleOne(upload)) return blocked('Native upload dialog unavailable');
      const file = upload.locator('input[type="file"][accept*=".pdf"]');
      if (await file.count() !== 1) return blocked('Unique approved file input unavailable');
      const http = await guardedUpload(file);
      if (!http?.ok()) return blocked('Native upload context changed or the upload was not acknowledged');
      const envelope = await http.json();
      const uploaded = envelope?.code === 0 ? envelope.data : null;
      if (!uploaded || uploaded.fileName !== filename || typeof uploaded.originalOssKey !== 'string' || !uploaded.originalOssKey ||
          Number(uploaded.size) !== size) return blocked('Native upload was not acknowledged');
      const confirm = upload.locator('.footer .btns').getByRole('button', { name: '確認', exact: true });
      if (!await visibleOne(confirm) || !await guardedSend('save_ready', confirm, { originalOssKey: uploaded.originalOssKey }))
        return blocked('Native save confirmation or upload identity changed');
      for (let i = 0; i < 6 && Date.now() < deadline - 9000; i++) {
        const list = await native('guard', { baselineIds: p.baselineIds });
        if (list.ok && list.resumeId && list.originalOssKey === uploaded.originalOssKey) {
          chosen = { resumeId: list.resumeId, originalOssKey: list.originalOssKey }; break;
        }
        await page.waitForTimeout(500);
      }
      if (!chosen.resumeId) return blocked('Approved uploaded resume not verified; never send default');
    }
    if ((!await visibleOne(picker) && !await openResumeFlow()) || !await visibleOne(picker)) return blocked('Native resume picker unavailable');
    const index = await resumeRadio();
    if (index < 0) return blocked('Approved resume picker row ambiguous');
    await radios.nth(index).locator('xpath=..').click({ timeout: budget(4000) });
    const stillSelected = async () => await resumeRadio() === index &&
      await radios.nth(index).getAttribute('data-testid') === 'radio-checked-iconIcon' && canonical(page.url()) === targetUrl;
    if (!await stillSelected()) return blocked('Approved resume selection or identity changed before send');
    const send = picker.getByRole('button', { name: '發送', exact: true });
    if (!await visibleOne(send)) return blocked('Native resume confirmation unavailable');
    const sendBaseline = await guardedSend('resume_ready', send, chosen, radios.nth(index));
    if (!sendBaseline) return blocked('Approved resume, peer or fresh pre-send history changed before send');
    for (let i = 0; i < 8 && Date.now() < deadline - 9000; i++) {
      const receipt = await native('receipt', { kind: 'resume', sendBaseline });
      if (receipt.ok) return { status: 'ok', cv: { jobId: input.jobId, conversationId: p.conversationId,
        messageId: receipt.messageId, resumeSha256: sendBaseline.resume.verifiedSha256,
        proof: `dispatch acknowledgement and fresh server history for the verified immutable resume version, message ${receipt.messageId} peer ${p.conversationId}` } };
      await page.waitForTimeout(500);
    }
    return blocked('Fresh server history resume receipt unavailable; never retry');
  } catch (_) { return blocked('Native browser action uncertain; never retry'); }
}
