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
    const element = () => ({
        listeners: {}, style: {}, dataset: {}, children: [], value: '',
        get innerHTML() { return this.html || 'Upload'; },
        set innerHTML(value) { this.html = value; htmlWrites.push(value); },
        classList: { add() {}, remove() {} },
        appendChild(child) { this.children.push(child); child.parentNode = this; },
        replaceChildren() { this.children = []; },
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
            body: element()
        },
        localStorage: { getItem() { return null; }, removeItem() {} },
        console: { log() {}, warn() {}, error() {} },
        fetch, FormData, Blob, crypto: webcrypto,
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
        return new Response('{"error":"Disk full"}', { status: 500 });
    });
    app.run("currentUsername = 'alice'");
    const file = new Blob(['content']);
    file.name = 'file.txt';
    await app.run('fileInput').listeners.change({ target: { files: [file] } });
    assert.deepEqual(calls, ['/api/files/upload-chunk']);
    assert.equal(app.context.progressEvents.at(-1).status, 'FAILED');
    assert.equal(app.context.progressEvents.at(-1).message, 'Disk full');
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
