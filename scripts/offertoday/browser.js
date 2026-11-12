async page => {
  const input = __PAYLOAD__;
  const deadline = Date.now() + 64000;
  const budget = maximum => {
    const left = deadline - Date.now();
    if (left <= 0) throw Error('Action deadline exceeded');
    return Math.min(maximum, left);
  };
  const blocked = reason => ({ status: 'blocked', reason });
  const origin = 'https://www.offertoday.com';
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
  if (input.operation === 'discover') {
    if (!jobPattern.test(here.pathname) && !/^\/hk\/(?:recommend|search)[^/]*(?:\/|$)/i.test(here.pathname))
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
    if (jobPattern.test(here.pathname)) observed.cards.unshift({ url: page.url(), ...observed.detail });
    const jobs = [], seen = new Set();
    for (const card of observed.cards) {
      const url = canonical(card.url);
      if (!url || seen.has(url)) continue;
      seen.add(url);
      if (!card.title || !card.company) return blocked('Loaded job has incomplete public title/company');
      jobs.push({ url, title: card.title, company: card.company, description: card.description });
      if (jobs.length === 40) break;
    }
    return jobs.length || observed.search ? { status: 'ok', jobs } : blocked('No recognized loaded job or search view');
  }
  if (!['preflight', 'send_text', 'send_resume'].includes(input.operation)) return blocked('Unrecognized browser operation');
  if (typeof input.jobId !== 'string' || !jobPattern.test(`/hk/job/${input.jobId}`) ||
      typeof input.title !== 'string' || !input.title.trim() || typeof input.company !== 'string' || !input.company.trim())
    return blocked('Approved job identity is incomplete');
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
  const version = await page.evaluate(() => [...document.scripts].some(s => {
    const u = new URL(s.src || location.href);
    return u.origin === location.origin && u.pathname === '/static/js/index.BKzheqN_.js';
  }));
  if (!version) return blocked('OfferToday native client version is not recognized');
  const digestPattern = /^[a-f0-9]{64}$/;
  const filename = input.operation === 'preflight' ? input.resumeFilename : input.preflight?.resumeFilename;
  const sha = input.operation === 'preflight' ? input.resumeSha256 : input.preflight?.resumeSha256;
  const size = input.operation === 'preflight' ? input.resumeSize : input.preflight?.resumeSize;
  if (typeof filename !== 'string' || !filename || !digestPattern.test(sha || '') ||
      !Number.isSafeInteger(size) || size <= 0 || size > 10 * 1024 * 1024)
    return blocked('Approved resume identity is incomplete');

  // This is the actual root Jotai store and the versioned entry's exported aliases.
  // All API calls below run in the page and return only bounded, non-sensitive metadata.
  const native = async (phase, extra = {}) => page.evaluate(async ({ phase, input, extra, targetUrl, filename, sha, size, deadline }) => {
    const fail = () => ({ ok: false });
    const timeout = promise => {
      const left = Math.min(8000, deadline - Date.now());
      if (left <= 0) throw Error('Action deadline exceeded');
      return Promise.race([promise, new Promise((_, reject) => setTimeout(() => reject(Error('timeout')), left))]);
    };
    const id = value => typeof value === 'string' ?
      (value.length && value.length <= 256 && value === value.trim() ? value : null) :
      (Number.isSafeInteger(value) && value >= 0 ? String(value) : null);
    const time = value => {
      if (Number.isSafeInteger(value) && value >= 0) return value;
      if (typeof value === 'string' && /^(0|[1-9][0-9]*)$/.test(value)) {
        const parsed = Number(value);
        return Number.isSafeInteger(parsed) ? parsed : null;
      }
      return null;
    };
    const hash = async blob => {
      if (!(blob instanceof Blob) || blob.size !== size || blob.size > 10 * 1024 * 1024) return false;
      const digest = await crypto.subtle.digest('SHA-256', await blob.arrayBuffer());
      return [...new Uint8Array(digest)].map(b => b.toString(16).padStart(2, '0')).join('') === sha;
    };
    try {
      if (location.origin !== 'https://www.offertoday.com' || location.href.split(/[?#]/)[0] !== targetUrl) return fail();
      const [entry, lang] = await timeout(Promise.all([
        import('/static/js/index.BKzheqN_.js'), import('/static/js/lang-73AtwcZ1.js')]));
      const store = lang.s, api = entry.I;
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
      if (job?.jobId !== input.jobId || normalize(job.jobName) !== normalize(input.title) ||
          ![job.companyName, job.brandName].some(s => normalize(s) === normalize(input.company)) ||
          typeof job.userId !== 'string' || !job.userId || job.userId.length > 256) return fail();
      const peer = job.userId;
      if (phase === 'preflight') {
        await timeout(store.set(entry.cs, peer)); // Native chat opener; never click Chat/Apply.
      }
      if (store.get(entry.cI) !== peer) return fail();
      const intact = () => Date.now() < deadline && location.href.split(/[?#]/)[0] === targetUrl &&
        store.get(entry.aD)?.upsn === owner && store.get(entry.cI) === peer;
      const p = input.preflight;
      if (phase !== 'preflight' && (!p || p.jobId !== job.jobId || p.conversationId !== peer ||
          p.ownerUpsn !== owner || p.resumeFilename !== filename || p.resumeSha256 !== sha ||
          p.resumeSize !== size)) return fail();
      const result = await timeout(api.chat.getHistoryMsgs({ friendId: peer, lastMessageId: -1, pageSize: 30 }));
      if (!intact() || !result || !Array.isArray(result.resultList) || result.resultList.length > 30 ||
          typeof result.hasMore !== 'boolean' || !Number.isSafeInteger(result.pageSize)) return fail();
      const records = result.resultList;
      if (records.some(r => !id(r.messageId) || time(r.addTime) === null)) return fail();
      const baselineIds = records.map(r => id(r.messageId));
      if (new Set(baselineIds).size !== baselineIds.length) return fail();
      const latestTime = Math.max(0, ...records.map(r => time(r.addTime)));
      const contact = records.find(r => id(r.messageId) === input.contactMessageId &&
        r.fromSn === owner && r.toSn === peer && r.mediaType === 1 && [0, 1, 2].includes(r.status));
      if (phase !== 'preflight' && input.operation === 'send_resume' && !contact) return fail();
      if (phase !== 'preflight' && (!Array.isArray(p.baselineIds) ||
          p.baselineIds.some(v => typeof v !== 'string' || !id(v)) ||
          new Set(p.baselineIds).size !== p.baselineIds.length ||
          !Number.isSafeInteger(p.baselineTime) || latestTime < p.baselineTime ||
          (input.operation === 'send_text' && phase !== 'receipt' &&
            JSON.stringify(baselineIds) !== JSON.stringify(p.baselineIds)))) return fail();
      if (phase === 'receipt') {
        const fresh = records.filter(r => !p.baselineIds.includes(id(r.messageId)) &&
          id(r.messageId) !== extra.contactMessageId && time(r.addTime) >= p.baselineTime &&
          r.fromSn === owner && r.toSn === peer && [0, 1, 2].includes(r.status));
        // Server sequence disambiguates equal timestamps; an unknown tie is not proof of a later send.
        const afterContact = r => contact && (r.addTime > contact.addTime ||
          (r.addTime === contact.addTime && Number.isSafeInteger(r.seq) &&
            Number.isSafeInteger(contact.seq) && contact.seq >= 0 && r.seq > contact.seq));
        const matches = fresh.filter(r => extra.kind === 'text' ?
          (r.mediaType === 1 && r.body?.text?.content === extra.message) :
          (r.mediaType === 6 && afterContact(r) && r.body?.compressiveCard?.type === 10 && (() => {
            try { return JSON.parse(r.body.compressiveCard.content).resumeId === extra.resumeId; }
            catch (_) { return false; }
          })()));
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
        const blob = await timeout(api.account.getDownloadAttach({ encryptAttachId: a.encryptAttachId },
          { responseType: 'blob', headers: { responseType: 'blob' } }));
        if (!await hash(blob) || !intact()) return fail();
        chosen = { resumeId: a.encryptAttachId, originalOssKey: a.originalOssKey };
      } else if (list.resume.length >= 3 || phase === 'verify') return fail();
      if (phase === 'verify') {
        if (!chosen || chosen.resumeId !== extra.resumeId || chosen.originalOssKey !== extra.originalOssKey) return fail();
      }
      if (phase === 'preflight') return { ok: true, jobId: job.jobId, conversationId: peer,
        ownerUpsn: owner, baselineIds, baselineTime: latestTime,
        resumeFilename: filename, resumeSha256: sha, resumeSize: size,
        resumeId: chosen?.resumeId || null, originalOssKey: chosen?.originalOssKey || null,
        attachmentCount: list.resume.length };
      return { ok: true, resumeId: chosen?.resumeId || null,
        originalOssKey: chosen?.originalOssKey || null, attachmentCount: list.resume.length };
    } catch (_) { return fail(); }
  }, { phase, input, extra, targetUrl, filename, sha, size, deadline });
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
      if ((await editor.innerText()).trim() !== input.message || canonical(page.url()) !== targetUrl ||
          !(await native('guard', { baselineIds: p.baselineIds })).ok)
        return blocked('Approved text or peer changed before send');
      await editor.press('Enter', { timeout: budget(4000) }); // Exactly one native irreversible send.
      for (let i = 0; i < 8 && Date.now() < deadline - 9000; i++) {
        const receipt = await native('receipt', { kind: 'text', message: input.message, baselineIds: p.baselineIds });
        if (receipt.ok) return { status: 'ok', contact: { jobId: input.jobId, conversationId: p.conversationId,
          messageId: receipt.messageId, initialMessageSha256: input.messageSha256,
          proof: `fresh server history own text ${receipt.messageId} peer ${p.conversationId}` } };
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
      const response = page.waitForResponse(r => r.url().split('?')[0].endsWith('/wapi/geek/mine/attach/upload'), { timeout: budget(12000) });
      await file.setInputFiles(input.resumePath, { timeout: budget(5000) }); // Selection auto-uploads once.
      const http = await response;
      const envelope = await http.json();
      const uploaded = envelope?.code === 0 ? envelope.data : null;
      if (!uploaded || uploaded.fileName !== filename || !uploaded.originalOssKey ||
          Number(uploaded.size) !== size) return blocked('Native upload was not acknowledged');
      const confirm = upload.locator('.footer .btns').getByRole('button', { name: '確認', exact: true });
      if (!await visibleOne(confirm)) return blocked('Native save confirmation unavailable');
      await confirm.click({ timeout: budget(4000) }); // Save newly uploaded resume; not a send.
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
    if (await resumeRadio() !== index || await radios.nth(index).getAttribute('data-testid') !== 'radio-checked-iconIcon' ||
        canonical(page.url()) !== targetUrl ||
        !(await native('verify', { baselineIds: p.baselineIds, ...chosen })).ok)
      return blocked('Approved resume selection or identity changed before send');
    const send = picker.getByRole('button', { name: '發送', exact: true });
    if (!await visibleOne(send)) return blocked('Native resume confirmation unavailable');
    await send.click({ timeout: budget(4000) }); // Exactly one irreversible native resume send.
    for (let i = 0; i < 8 && Date.now() < deadline - 9000; i++) {
      const receipt = await native('receipt', { kind: 'resume', resumeId: chosen.resumeId,
        contactMessageId: input.contactMessageId, baselineIds: p.baselineIds });
      if (receipt.ok) return { status: 'ok', cv: { jobId: input.jobId, conversationId: p.conversationId,
        messageId: receipt.messageId, resumeSha256: sha,
        proof: `fresh server history own resume ${receipt.messageId} peer ${p.conversationId}` } };
      await page.waitForTimeout(500);
    }
    return blocked('Fresh server history resume receipt unavailable; never retry');
  } catch (_) { return blocked('Native browser action uncertain; never retry'); }
}
