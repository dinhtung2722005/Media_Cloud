const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { webcrypto } = require('node:crypto');

function application(fetch, { realProgress = false } = {}) {
    const elements = new Map();
    const timers = new Map();
    let nextTimer = 0;
    const htmlWrites = [];
    const stored = new Map();
    const element = (tag = 'div') => ({
        tagName: tag.toUpperCase(), attributes: {},
        listeners: {}, style: {}, dataset: {}, children: [], value: '',
        get innerHTML() { return this.html || 'Upload'; },
        set innerHTML(value) { this.html = value; htmlWrites.push(value); },
        get classList() {
            const node = this;
            return {
                add(...tokens) { node.className = [...new Set([...(node.className || '').split(' ').filter(Boolean), ...tokens])].join(' '); },
                remove(...tokens) { node.className = (node.className || '').split(' ').filter(token => !tokens.includes(token)).join(' '); },
                contains(token) { return (node.className || '').split(' ').includes(token); }
            };
        },
        setAttribute(name, value) { this.attributes[name] = String(value); },
        getAttribute(name) { return this.attributes[name]; },
        focus() {}, select() {},
        appendChild(child) { this.children.push(child); child.parentNode = this; },
        replaceChildren(...children) { this.children = []; children.forEach(child => this.appendChild(child)); },
        remove() { if (this.parentNode) this.parentNode.children = this.parentNode.children.filter(node => node !== this); },
        addEventListener(type, listener) { this.listeners[type] = listener; },
        click() { return this.listeners.click?.({ preventDefault() {}, stopPropagation() {} }); }
    });
    const context = vm.createContext({
        document: {
            getElementById(id) {
                if (!elements.has(id)) elements.set(id, element());
                return elements.get(id);
            },
            createElement: element,
            addEventListener() {},
            body: element()
        },
        localStorage: { getItem(key) { return stored.get(key) || null; }, setItem(key, value) { stored.set(key, value); }, removeItem(key) { stored.delete(key); } },
        bootstrap: {
            Toast: { getOrCreateInstance() { return { show() {} }; } },
            Modal: class {
                constructor(element) { this.element = element; }
                show() { this.element.listeners['shown.bs.modal']?.(); }
                hide() { this.element.listeners['hidden.bs.modal']?.(); }
            }
        },
        console: { log() {}, warn() {}, error() {} },
        fetch, FormData, Blob, URL, URLSearchParams, TextEncoder, crypto: webcrypto, location: { origin: 'https://cloud.test' },
        setTimeout(callback) { const id = ++nextTimer; timers.set(id, callback); return id; },
        clearTimeout(id) { timers.delete(id); },
        progressEvents: []
    });
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/app.js'), 'utf8'), context);
    if (!realProgress) vm.runInContext('handleRealtimeProgress = data => progressEvents.push(data)', context);
    return { context, elements, timers, htmlWrites, run: source => vm.runInContext(source, context) };
}

const ok = () => new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });

test('uploads 5 MB chunks and merges into the original folder after token renewal', async () => {
    const calls = [];
    let renew = true;
    const app = application(async (url, options) => {
        calls.push({ url, options });
        if (url === '/api/files/upload-chunk' && renew) {
            renew = false;
            return new Response('', { status: 401 });
        }
        if (url === '/api/files/upload-chunk') app.run("currentFolderId = 'new-folder'");
        return ok();
    });
    app.run("currentUsername = 'alice'; currentFolderId = 'original-folder'");
    const file = new Blob([new Uint8Array(5 * 1024 * 1024 + 3)]);
    file.name = 'video.mp4';
    await app.run('fileInput').listeners.change({ target: { files: [file] } });

    assert.deepEqual(calls.map(call => call.url), [
        '/api/files/uploads',
        '/api/files/upload-chunk', '/api/auth/refresh', '/api/files/upload-chunk',
        '/api/files/upload-chunk', '/api/files/merge-chunks'
    ]);
    const chunks = calls.filter(call => call.url === '/api/files/upload-chunk');
    assert.deepEqual(chunks.map(call => call.options.body.get('file').size), [5 * 1024 * 1024, 5 * 1024 * 1024, 3]);
    assert.deepEqual(chunks.map(call => call.options.body.get('chunkIndex')), ['0', '0', '1']);
    const merge = JSON.parse(calls.at(-1).options.body);
    assert.equal(merge.totalChunks, 2);
    assert.equal(merge.folderId, 'original-folder');
    assert.equal(merge.fileGuid, chunks[0].options.body.get('fileGuid'));
    assert.equal(app.context.progressEvents.at(-1).status, 'COMPLETED');
    assert.equal(app.elements.get('btnUploadDropdown').disabled, false);
});

test('concurrent expired requests share one refresh request', async () => {
    const attempts = new Map();
    let refreshCount = 0;
    const app = application(async url => {
        if (url === '/api/auth/refresh') { refreshCount++; return ok(); }
        const count = (attempts.get(url) || 0) + 1;
        attempts.set(url, count);
        return count === 1 ? new Response('', { status: 401 }) : ok();
    });
    const responses = await app.run("Promise.all([fetchWithAuth('/first'), fetchWithAuth('/second')])");
    assert.equal(refreshCount, 1);
    assert.ok(responses.every(response => response.ok));
});

test('failed chunks stop upload before merge and restore the upload button', async () => {
    const calls = [];
    const app = application(async url => {
        calls.push(url);
        if (url === '/api/files/uploads') return ok();
        return new Response('{"error":"Disk full"}', { status: 500 });
    });
    app.run("currentUsername = 'alice'");
    const file = new Blob(['content']);
    file.name = 'file.txt';
    await app.run('fileInput').listeners.change({ target: { files: [file] } });
    assert.deepEqual(calls, ['/api/files/uploads', '/api/files/upload-chunk']);
    assert.equal(app.context.progressEvents.at(-1).status, 'FAILED');
    assert.match(app.context.progressEvents.at(-1).message, /Disk full/);
    assert.equal(app.elements.get('btnUploadDropdown').disabled, false);
});

test('logout cancels WebSocket reconnect and ignores errors from the old socket', async () => {
    const app = application(async () => ok());
    let connectionError;
    app.context.SockJS = function () {};
    app.context.Stomp = {
        over() {
            return {
                connected: false, ws: { close() {} },
                connect(headers, success, error) { connectionError = error; error(); }
            };
        }
    };
    app.run("currentUsername = 'alice'");
    await app.run("connectWebSocket('alice')");
    assert.equal(app.timers.size, 1);
    await app.elements.get('btnLogout').click();
    assert.equal(app.timers.size, 0);
    connectionError();
    assert.equal(app.timers.size, 0);
    assert.equal(app.run('currentUsername'), null);
});

test('file names, folder names and task names remain text instead of HTML', () => {
    const app = application(async () => ok(), { realProgress: true });
    const payload = '<img src=x onerror="globalThis.compromised=1">';
    app.context.hostileName = payload;
    app.run("renderMedia({folders:[{id:'folder',folderName:hostileName}],files:[{id:'file',title:hostileName,extension:'mp4'}]},false)");
    app.run("handleRealtimeProgress({taskId:'task',taskName:hostileName,progress:25,status:'PROCESSING'})");
    const descend = node => [node, ...node.children.flatMap(descend)];
    const nodes = [...descend(app.elements.get('media-list')), ...descend(app.elements.get('progress-list'))];
    assert.ok(nodes.filter(node => node.textContent === payload).length >= 3);
    assert.ok(app.htmlWrites.every(value => !String(value).includes(payload)));
    assert.ok(nodes.every(node => node.onclick === undefined));
});

const descendants = node => [node, ...node.children.flatMap(descendants)];
const fileTitles = app => descendants(app.elements.get('media-list')).filter(node => node.className === 'file-title').map(node => node.textContent);

test('Vietnamese search combines with type filters while overview totals describe the entire folder', () => {
    const app = application(async () => ok());
    app.run("renderMedia({folders:[{id:'folder',folderName:'Nhạc'}],files:[{id:'a',title:'Nhạc dịu dàng.mp3',extension:'mp3',fileSize:1024},{id:'v',title:'Dịu dàng.mp4',extension:'mp4',fileSize:2048}]},false)");
    app.elements.get('file-search').listeners.input({ target: { value: 'diu dang' } });
    assert.equal(fileTitles(app).length, 2);
    app.elements.get('filter-audio').click();
    assert.deepEqual(fileTitles(app), ['Nhạc dịu dàng.mp3']);
    assert.equal(app.elements.get('stat-audio').textContent, 1);
    assert.equal(app.elements.get('count-all').textContent, 2);
    assert.equal(app.elements.get('nav-audio').getAttribute('aria-pressed'), 'true');
    assert.equal(app.elements.get('visible-count').textContent, '1 mục');
});

test('size sorting and list view preserve actual file metadata', () => {
    const app = application(async () => ok());
    app.run("renderMedia({files:[{id:'a',title:'A.mp4',extension:'mp4',fileSize:50},{id:'b',title:'B.pdf',extension:'pdf',fileSize:100}]},false)");
    app.elements.get('file-sort').listeners.change({ target: { value: 'size-desc' } });
    assert.deepEqual(fileTitles(app), ['B.pdf', 'A.mp4']);
    app.elements.get('btnListView').click();
    assert.equal(app.elements.get('media-list').dataset.view, 'list');
    assert.equal(app.elements.get('btnListView').getAttribute('aria-pressed'), 'true');
    assert.equal(app.elements.get('btnGridView').getAttribute('aria-pressed'), 'false');
    assert.equal(app.run("localStorage.getItem('cloudmedia-view')"), 'list');
});

test('an older folder response cannot replace the latest navigation', async () => {
    const pending = new Map();
    const app = application(url => new Promise(resolve => pending.set(url, resolve)));
    const old = app.run("loadFolder('old')");
    const latest = app.run("loadFolder('new')");
    pending.get('/api/folders/new')(new Response(JSON.stringify({ folderInfo: { id: 'new', folderName: 'Mới' }, files: [] })));
    await latest;
    pending.get('/api/folders/old')(new Response(JSON.stringify({ folderInfo: { id: 'old', folderName: 'Cũ' }, files: [] })));
    await old;
    assert.equal(app.run('currentFolderId'), 'new');
    assert.equal(app.elements.get('page-title').textContent, 'Mới');
    assert.equal(app.elements.get('media-list').getAttribute('aria-busy'), 'false');
});

test('creating a folder from the dialog posts to the folder captured when opened', async () => {
    const calls = [];
    const app = application(async (url, options) => { calls.push({ url, options }); return ok(); });
    app.run("currentFolderId='parent'; createFolder(); currentFolderId='elsewhere'; document.getElementById('action-input').value='Bản nháp'");
    await app.elements.get('action-form').listeners.submit({ preventDefault() {} });
    assert.equal(calls[0].url, '/api/folders');
    assert.deepEqual(JSON.parse(calls[0].options.body), { folderName: 'Bản nháp', parentId: 'parent' });
    assert.equal(calls.length, 1);
});

test('login and registration forms preserve intentional password whitespace', async () => {
    const calls = [];
    const app = application(async (url, options) => { calls.push({ url, options }); return ok(); });
    app.run("document.getElementById('reg-username').value='alice'; document.getElementById('reg-password').value=' pass word '; document.getElementById('reg-confirm-password').value=' pass word '");
    await app.elements.get('register-section').listeners.submit({ preventDefault() {} });
    assert.equal(JSON.parse(calls[0].options.body).password, ' pass word ');
    app.run("document.getElementById('login-password').value=' pass word '");
    await app.elements.get('login-section').listeners.submit({ preventDefault() {} });
    const login = calls.find(call => call.url === '/api/auth/login');
    assert.equal(JSON.parse(login.options.body).password, ' pass word ');
    assert.equal(app.run('currentUsername'), 'alice');
});

test('retrying remains active and a terminal failure cannot be overwritten by stale progress', () => {
    const app = application(async () => ok(), { realProgress: true });
    app.run("handleRealtimeProgress({taskId:'task',taskName:'Video',status:'RETRYING'})");
    assert.equal(app.timers.size, 0);
    app.run("handleRealtimeProgress({taskId:'task',taskName:'Video',status:'PROCESSING',progress:42})");
    assert.equal(app.timers.size, 0);
    assert.equal(app.run("progressRows.get('task').percent.textContent"), '42%');
    app.run("handleRealtimeProgress({taskId:'task',taskName:'Video',status:'FAILED'});handleRealtimeProgress({taskId:'task',status:'RUNNING',progress:10})");
    assert.equal(app.run("progressRows.get('task').percent.textContent"), 'Thất bại');
});

test('RabbitMQ job cancellation uses the owner-protected job endpoint', async () => {
    const calls = [];
    const app = application(async (url, options) => { calls.push({ url, options }); return ok(); }, { realProgress: true });
    const id = '7cf9cfe2-74f9-4025-9a1b-3edbefaab876';
    app.run("handleRealtimeProgress({taskId:'" + id + "',taskName:'Video',status:'QUEUED'})");
    await app.run("progressRows.get('" + id + "').cancel").click();
    assert.equal(calls[0].url, '/api/media/jobs/' + id);
    assert.equal(calls[0].options.method, 'DELETE');
    assert.equal(app.run("progressRows.get('" + id + "').percent.textContent"), 'Đang hủy');
    assert.equal(app.run("progressRows.get('" + id + "').cancel.disabled"), true);
});

test('upload retry keeps the same ID and skips chunks already received by the server', async () => {
    const calls = []; let initialized = 0; let fail = true;
    const app = application(async (url, options) => {
        calls.push({ url, options });
        if (url === '/api/files/uploads') { initialized++; return new Response(JSON.stringify({status:'OPEN',receivedChunks: initialized === 1 ? [] : [0]})); }
        if (url === '/api/files/upload-chunk' && options.body.get('chunkIndex') === '1' && fail) { fail = false; return new Response('{"error":"Disconnected"}', {status:503}); }
        return ok();
    });
    app.run("currentUsername='alice'");const file = new Blob([new Uint8Array(5242881)]);file.name='resume.mp4';file.lastModified=123;
    await app.run('uploadFile')(file);await app.run('uploadFile')(file);
    const init=calls.filter(call=>call.url==='/api/files/uploads').map(call=>JSON.parse(call.options.body));assert.equal(init[0].uploadId,init[1].uploadId);
    assert.deepEqual(calls.filter(call=>call.url==='/api/files/upload-chunk').map(call=>call.options.body.get('chunkIndex')),['0','1','1']);
    assert.equal(calls.filter(call=>call.url==='/api/files/merge-chunks').length,1);assert.equal(app.context.progressEvents.at(-1).status,'COMPLETED');
});

test('polling restores active jobs and reconciles a missed completion after reload', async () => {
    const id='7cf9cfe2-74f9-4025-9a1b-3edbefaab876';let active=true;
    const app=application(async url=>new Response(JSON.stringify(url.startsWith('/api/media/jobs?')?{jobs:active?[{jobId:id,type:'YOUTUBE',status:'RETRYING',progress:20}]:[]}:{jobId:id,type:'YOUTUBE',status:'COMPLETED',progress:100})),{realProgress:true});
    app.run("currentUsername='alice'");await app.run('syncJobs()');assert.equal(app.run("progressRows.get('"+id+"').percent.textContent"),'Đang thử lại');
    active=false;await app.run('syncJobs()');assert.equal(app.run("progressRows.get('"+id+"').percent.textContent"),'Hoàn tất');
    assert.equal(app.run('rememberedJobs().length'),0);
});

test('server pagination appends results and sends the selected search and type', async () => {
    const calls=[];
    const app=application(async url=>{calls.push(url);const second=url.includes('page=1');return new Response(JSON.stringify({page:second?1:0,size:50,totalFiles:2,totalFolders:0,hasMore:!second,folders:[],files:[{id:second?'b':'a',title:second?'B.mp3':'A.mp3',extension:'mp3'}]}));});
    app.run("currentUsername='alice'");await app.run('loadFolder(null)');await app.run('loadFolder(null,true)');assert.deepEqual(fileTitles(app),['A.mp3','B.mp3']);
    app.run("searchTerm='bai hat';activeType='audio'");await app.run('loadFolder(null)');assert.match(calls.at(-1),/q=bai\+hat/);assert.match(calls.at(-1),/type=audio/);
});

test('file sharing creates an expiring URL, copies it, and revokes the same link', async () => {
    const calls=[];let active=false;let copied='';const token='A'.repeat(43);
    const app=application(async(url,options={})=>{
        calls.push({url,options});
        if(options.method==='POST'){active=true;return new Response(JSON.stringify({id:'share-id',path:'/s/'+token,expiresAt:'2026-10-27T00:00:00Z'}),{status:201});}
        if(options.method==='DELETE'){active=false;return new Response(null,{status:204});}
        return new Response(JSON.stringify(active?[{id:'share-id',status:'ACTIVE',expiresAt:'2026-10-27T00:00:00Z'}]:[]));
    });
    app.context.navigator={clipboard:{async writeText(value){copied=value;}}};app.run("currentUsername='alice'");
    await app.run("shareFile({id:'video-id',title:'My video.mp4'})");
    await app.elements.get('share-create').click();
    assert.equal(app.elements.get('share-url').value,'https://cloud.test/s/'+token);
    assert.equal(app.elements.get('share-result').classList.contains('hidden'),false);
    const post=calls.find(call=>call.options.method==='POST');assert.equal(post.url,'/api/files/video-id/shares');assert.deepEqual(JSON.parse(post.options.body),{expiresInHours:24});
    await app.elements.get('share-copy').click();assert.equal(copied,'https://cloud.test/s/'+token);
    const revoke=descendants(app.elements.get('share-links')).find(node=>String(node.className).includes('share-revoke'));await revoke.click();
    assert.ok(calls.some(call=>call.url==='/api/files/video-id/shares/share-id'&&call.options.method==='DELETE'));assert.equal(app.elements.get('share-url').value,'');
});

test('a delayed link creation cannot expose its URL after the dialog has closed', async () => {
    let finish;const app=application(async(url,options={})=>options.method==='POST'?new Promise(resolve=>{finish=resolve;}):new Response('[]'));
    app.run("currentUsername='alice'");await app.run("shareFile({id:'file-a',title:'A'})");const pending=app.elements.get('share-create').click();
    app.elements.get('share-modal').listeners['hidden.bs.modal']();
    finish(new Response(JSON.stringify({id:'old',path:'/s/'+'B'.repeat(43),expiresAt:'2026-10-27T00:00:00Z'}),{status:201}));await pending;
    assert.equal(app.elements.get('share-url').value,'');assert.equal(app.elements.get('share-result').classList.contains('hidden'),true);
});
