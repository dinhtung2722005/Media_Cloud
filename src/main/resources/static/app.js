const authContainer = document.getElementById('auth-container');
const appContainer = document.getElementById('app-container');
const authMessage = document.getElementById('auth-message');
const welcomeText = document.getElementById('welcome-text');
const mediaList = document.getElementById('media-list');
const loginSection = document.getElementById('login-section');
const registerSection = document.getElementById('register-section');
const btnUploadDropdown = document.getElementById('btnUploadDropdown');
const fileInput = document.createElement('input');
fileInput.type = 'file';
fileInput.style.display = 'none';
fileInput.setAttribute('aria-label', 'Chọn tệp để tải lên');
document.body.appendChild(fileInput);

let currentUsername = localStorage.getItem('username');
let currentFolderId = null;
let stompClient = null;
let refreshPromise = null;
let reconnectTimer = null;
let jobsPollTimer = null;
let syncingJobs = false;
let searchTimer = null;
let webSocketGeneration = 0;
let folderLoadVersion = 0;
let folderLoading = false;
let folderData = null;
let insideFolder = false;
let activeType = 'all';
let searchTerm = '';
let sortMode = 'name';
let viewMode = localStorage.getItem('cloudmedia-view') === 'list' ? 'list' : 'grid';
let uploadInProgress = false;
let dialogState = null;
let actionModal = null;
let previewModal = null;
let currentPreview = null;
let shareModal = null;
let sharedFile = null;
let shareVersion = 0;
const progressRows = new Map();
const knownFolders = new Map();
const fileTypes = ['all', 'video', 'image', 'audio', 'document'];
const typeNames = { all: 'Tất cả tệp', video: 'Video', image: 'Hình ảnh', audio: 'Âm thanh', document: 'Tệp khác' };
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function makeElement(tag, className = '', text = '') {
    const node = document.createElement(tag);
    node.className = className;
    node.textContent = text;
    return node;
}
function icon(name) {
    const node = makeElement('span', 'icon icon-' + name);
    node.setAttribute('aria-hidden', 'true');
    return node;
}
function button(text, className, action) {
    const node = makeElement('button', className, text);
    node.type = 'button';
    node.addEventListener('click', action);
    return node;
}
function setText(id, text) {
    const node = document.getElementById(id);
    if (node) node.textContent = text;
}
function setActive(node, active) {
    if (!node) return;
    node.classList[active ? 'add' : 'remove']('is-active');
    node.setAttribute('aria-pressed', String(active));
}
function formatBytes(value) {
    const bytes = Math.max(0, Number(value) || 0);
    if (!bytes) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    const unit = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
    return (bytes / Math.pow(1024, unit)).toLocaleString('vi-VN', { maximumFractionDigits: 1 }) + ' ' + units[unit];
}
function fileType(file) {
    const ext = String(file.extension || '').toLowerCase();
    if (['mp4', 'webm', 'mov', 'mkv', 'm4v', 'avi', 'ogv'].includes(ext)) return 'video';
    if (['jpg', 'jpeg', 'png', 'gif', 'webp', 'avif', 'bmp', 'svg'].includes(ext)) return 'image';
    if (['mp3', 'wav', 'flac', 'aac', 'm4a', 'ogg', 'opus'].includes(ext)) return 'audio';
    return 'document';
}
function normalizeSearch(value) {
    return String(value || '').toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/đ/g, 'd');
}
function getFolders(data = folderData) {
    if (!data) return [];
    return insideFolder ? (data.subFolders || data.folders || []) : (Array.isArray(data) ? data : data.folders || []);
}
function getFiles() { return folderData?.files || []; }

async function refreshAccessToken() {
    if (!refreshPromise) {
        refreshPromise = fetch('/api/auth/refresh', { method: 'POST', credentials: 'include' })
            .then(response => response.ok)
            .finally(() => { refreshPromise = null; });
    }
    return refreshPromise;
}
async function fetchWithAuth(url, options = {}) {
    options.credentials = 'include';
    let response = await fetch(url, options);
    if (response.status === 401) {
        if (await refreshAccessToken()) response = await fetch(url, options);
        if (response.status === 401) {
            document.getElementById('btnLogout').click();
            throw new Error('Phiên đăng nhập hết hạn, vui lòng đăng nhập lại.');
        }
    }
    return response;
}
async function requestJson(url, options = {}, errorMessage = 'Không thể hoàn tất thao tác. Vui lòng thử lại.') {
    const response = await fetchWithAuth(url, options);
    const data = await response.json().catch(() => ({}));
    if (!response.ok) { const error = new Error(data.error || data.message || errorMessage); error.status = response.status; error.code = data.code; throw error; }
    return data;
}
function jsonOptions(method, data) {
    return { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) };
}
function newRequestId() {
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128;
    const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
    return [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join('-');
}
function idempotentOptions(data, key) { const options = jsonOptions('POST', data); options.headers['Idempotency-Key'] = key; return options; }

function showMainApp() {
    authContainer.classList.add('hidden');
    appContainer.classList.remove('hidden');
    welcomeText.textContent = currentUsername;
    setText('user-avatar', String(currentUsername || 'U').slice(0, 2).toUpperCase());
    connectWebSocket(currentUsername);
    loadFolder(null);
    syncJobs();
    scheduleJobPolling();
}
function jobStorageKey() { return 'cloudmedia-jobs:' + currentUsername; }
function rememberedJobs() { try { return JSON.parse(localStorage.getItem(jobStorageKey()) || '[]').filter(id => uuidPattern.test(id)).slice(-100); } catch (error) { return []; } }
function rememberJob(id, terminal) {
    if (!currentUsername || !uuidPattern.test(id)) return;
    const ids = new Set(rememberedJobs());
    if (terminal) ids.delete(id); else ids.add(id);
    localStorage.setItem(jobStorageKey(), JSON.stringify([...ids].slice(-100)));
}
function jobProgress(job) { return { ...job, taskId: job.jobId, taskName: job.type === 'CONVERT_MP3' ? 'Chuyển MP3' : 'Tải video YouTube' }; }
async function syncJobs() {
    if (!currentUsername || syncingJobs) return;
    syncingJobs = true;
    const username = currentUsername;
    try {
        const data = await requestJson('/api/media/jobs?active=true&size=100');
        if (username !== currentUsername) return;
        const active = new Set();
        for (const job of data.jobs || []) { active.add(job.jobId); handleRealtimeProgress(jobProgress(job)); }
        for (const id of rememberedJobs()) {
            if (username !== currentUsername) return;
            if (active.has(id)) continue;
            try { const job = await requestJson('/api/media/jobs/' + encodeURIComponent(id)); if (username === currentUsername && job.jobId) handleRealtimeProgress(jobProgress(job)); }
            catch (error) { if (error.status === 404) rememberJob(id, true); }
        }
    } catch (error) { /* WebSocket updates continue; polling retries automatically. */ }
    finally { syncingJobs = false; }
}
function scheduleJobPolling() {
    clearTimeout(jobsPollTimer);
    if (currentUsername) jobsPollTimer = setTimeout(async () => { await syncJobs(); scheduleJobPolling(); }, 10000);
}
function setConnection(connected) {
    const node = document.getElementById('connection-status');
    node.classList[connected ? 'add' : 'remove']('is-connected');
    setText('connection-label', connected ? 'Đã kết nối' : 'Đang kết nối lại');
}
async function connectWebSocket(username) {
    const generation = ++webSocketGeneration;
    const retry = () => {
        if (generation === webSocketGeneration && currentUsername === username) {
            setConnection(false);
            clearTimeout(reconnectTimer);
            reconnectTimer = setTimeout(() => connectWebSocket(username), 5000);
        }
    };
    try {
        const response = await fetchWithAuth('/api/folders');
        if (!response.ok) throw new Error('Không thể xác thực WebSocket');
        if (generation !== webSocketGeneration || currentUsername !== username) return;
        const client = Stomp.over(new SockJS('/ws'));
        stompClient = client;
        client.debug = null;
        client.connect({}, () => {
            if (generation !== webSocketGeneration) { client.disconnect(); return; }
            setConnection(true);
            syncJobs();
            client.subscribe('/user/queue/progress', response => {
                if (generation === webSocketGeneration) handleRealtimeProgress(JSON.parse(response.body));
            });
        }, retry);
    } catch (error) { retry(); }
}
function disconnectWebSocket() {
    webSocketGeneration++;
    clearTimeout(reconnectTimer);
    if (stompClient !== null) {
        if (stompClient.connected) stompClient.disconnect();
        else if (stompClient.ws) stompClient.ws.close();
        stompClient = null;
    }
    setConnection(false);
}

function showToast(message, kind = 'success') {
    const toastEl = document.getElementById('realtimeToast');
    if (!toastEl) return;
    const type = kind === 'bg-danger' || kind === 'error' ? 'error' : kind === 'info' ? 'info' : 'success';
    toastEl.className = 'toast toast-' + type;
    setText('toast-message', message);
    if (typeof bootstrap !== 'undefined') bootstrap.Toast.getOrCreateInstance(toastEl, { delay: 4500 }).show();
}

function handleRealtimeProgress(data) {
    const container = document.getElementById('progress-container');
    const list = document.getElementById('progress-list');
    let value = Number(data.progress || 0);
    if (data.progressLine) {
        const match = data.progressLine.match(/([\d.]+)%/);
        if (!match) return;
        value = Number(match[1]);
    }
    const progress = Number.isFinite(value) ? Math.max(0, Math.min(100, Math.round(value))) : 0;
    const taskId = String(data.taskId || 'default-task');
    const taskName = String(data.taskName || 'Đang xử lý');
    container.classList.remove('hidden');
    let task = progressRows.get(taskId);
    if (!task) {
        const row = makeElement('div', 'task-row');
        const symbol = makeElement('span', 'task-icon');
        symbol.appendChild(icon(taskId.startsWith('upload-') ? 'upload' : 'layers'));
        const detail = makeElement('div', 'task-detail');
        const heading = makeElement('div', 'task-heading');
        const name = makeElement('span', 'task-name');
        const percent = makeElement('span', 'task-percent');
        const track = makeElement('div', 'progress');
        const bar = makeElement('div', 'progress-bar');
        bar.setAttribute('role', 'progressbar');
        bar.setAttribute('aria-valuemin', '0');
        bar.setAttribute('aria-valuemax', '100');
        heading.appendChild(name);
        heading.appendChild(percent);
        track.appendChild(bar);
        detail.appendChild(heading);
        detail.appendChild(track);
        row.appendChild(symbol);
        row.appendChild(detail);
        let cancel = null;
        if (uuidPattern.test(taskId)) {
            cancel = button('Hủy', 'task-cancel', async () => {
                cancel.disabled = true;
                try {
                    const job = await requestJson('/api/media/jobs/' + encodeURIComponent(taskId), { method: 'DELETE' });
                    handleRealtimeProgress({ ...job, taskId, taskName, status: job.status || 'CANCEL_REQUESTED' });
                } catch (error) {
                    showToast(error.message, 'error');
                    cancel.disabled = false;
                }
            });
            cancel.setAttribute('aria-label', 'Hủy tác vụ ' + taskName);
            row.appendChild(cancel);
        }
        list.appendChild(row);
        task = { row, name, percent, bar, cancel, removeTimer: null, status: null, updatedAt: null };
        progressRows.set(taskId, task);
    }
    if (data.updatedAt && task.updatedAt && Date.parse(data.updatedAt) < Date.parse(task.updatedAt)) return;
    if (['COMPLETED', 'CANCELLED', 'FAILED'].includes(task.status)) return;
    task.updatedAt = data.updatedAt || task.updatedAt;
    task.status = data.status;
    clearTimeout(task.removeTimer);
    task.name.textContent = taskName;
    task.name.title = taskName;
    const labels = { PENDING_DISPATCH: 'Chờ gửi', QUEUED: 'Đang chờ', RETRYING: 'Đang thử lại', CANCEL_REQUESTED: 'Đang hủy' };
    task.percent.textContent = labels[data.status] || progress + '%';
    task.bar.className = 'progress-bar';
    task.bar.style.width = progress + '%';
    task.bar.setAttribute('aria-label', taskName);
    task.bar.setAttribute('aria-valuenow', String(progress));
    setText('active-task-count', progressRows.size);
    const terminal = ['COMPLETED', 'FAILED', 'CANCELLED'].includes(data.status);
    rememberJob(taskId, terminal);
    if (task.cancel) task.cancel.disabled = data.status === 'CANCEL_REQUESTED';
    if (task.cancel) task.cancel.classList[terminal ? 'add' : 'remove']('hidden');
    if (terminal) {
        const success = data.status === 'COMPLETED';
        const cancelled = data.status === 'CANCELLED';
        task.bar.className = 'progress-bar ' + (success ? 'bg-success' : cancelled ? 'bg-secondary' : 'bg-danger');
        task.percent.textContent = success ? 'Hoàn tất' : cancelled ? 'Đã hủy' : 'Thất bại';
        if (success) {
            task.bar.style.width = '100%';
            task.bar.setAttribute('aria-valuenow', '100');
        }
        showToast(success ? taskName + ' đã hoàn tất.' : cancelled ? 'Đã hủy ' + taskName.toLowerCase() + '.' : (data.message || 'Không thể hoàn tất tác vụ.'), success ? 'success' : cancelled ? 'info' : 'error');
        task.removeTimer = setTimeout(() => {
            task.row.remove();
            progressRows.delete(taskId);
            setText('active-task-count', progressRows.size);
            if (!list.children.length) container.classList.add('hidden');
        }, success ? 3500 : 6000);
        if (success) loadFolder(currentFolderId);
    }
}

function closeSidebar() {
    appContainer.classList.remove('sidebar-open');
    document.getElementById('sidebar-toggle').setAttribute('aria-expanded', 'false');
}
function setTypeFilter(type) {
    activeType = fileTypes.includes(type) ? type : 'all';
    for (const item of fileTypes) {
        setActive(document.getElementById('nav-' + item), item === activeType);
        setActive(document.getElementById('filter-' + item), item === activeType);
        if (item !== 'all') setActive(document.getElementById('stat-button-' + item), item === activeType);
    }
    closeSidebar();
    if (Number.isInteger(folderData?.page)) loadFolder(currentFolderId); else if (!folderLoading) renderVisibleMedia();
}
function setView(mode) {
    viewMode = mode === 'list' ? 'list' : 'grid';
    mediaList.dataset.view = viewMode;
    setActive(document.getElementById('btnGridView'), viewMode === 'grid');
    setActive(document.getElementById('btnListView'), viewMode === 'list');
    localStorage.setItem('cloudmedia-view', viewMode);
}
function navigateFolder(folderId) {
    searchTerm = '';
    document.getElementById('file-search').value = '';
    activeType = 'all';
    for (const type of fileTypes) { setActive(document.getElementById('nav-' + type), type === 'all'); setActive(document.getElementById('filter-' + type), type === 'all'); if (type !== 'all') setActive(document.getElementById('stat-button-' + type), false); }
    closeSidebar();
    return loadFolder(folderId);
}
function showLoading() {
    mediaList.replaceChildren();
    mediaList.setAttribute('aria-busy', 'true');
    const grid = makeElement('div', 'loading-grid');
    grid.setAttribute('aria-label', 'Đang tải thư viện');
    for (let i = 0; i < 4; i++) grid.appendChild(makeElement('div', 'skeleton-card'));
    mediaList.appendChild(grid);
}
async function loadFolder(folderId, append = false) {
    const version = ++folderLoadVersion;
    const username = currentUsername;
    folderLoading = true;
    if (!append) showLoading();
    try {
        const page = append ? (folderData?.page || 0) + 1 : 0;
        const params = new URLSearchParams({ page: String(page), size: '50', q: searchTerm.trim(), sort: sortMode, type: activeType });
        const suffix = page || searchTerm.trim() || sortMode !== 'name' || activeType !== 'all' ? '?' + params : '';
        const data = await requestJson((folderId ? '/api/folders/' + encodeURIComponent(folderId) : '/api/folders') + suffix, { method: 'GET' }, 'Không tìm thấy thư mục hoặc bạn không có quyền truy cập.');
        if (version !== folderLoadVersion || username !== currentUsername) return;
        if (append && folderData) {
            const key = folderId ? 'subFolders' : 'folders';
            data[key] = [...(folderData[key] || []), ...(data[key] || [])];
            data.files = [...(folderData.files || []), ...(data.files || [])];
        }
        currentFolderId = folderId;
        folderLoading = false;
        renderMedia(data, Boolean(folderId));
    } catch (error) {
        if (version !== folderLoadVersion || username !== currentUsername) return;
        folderLoading = false;
        mediaList.setAttribute('aria-busy', 'false');
        mediaList.replaceChildren();
        mediaList.appendChild(emptyState('Không thể mở thư viện', error.message, 'refresh', 'Thử lại', () => loadFolder(folderId)));
    }
}
function renderMedia(data, isInsideFolder) {
    folderData = data;
    insideFolder = Boolean(isInsideFolder);
    if (data.folderInfo) knownFolders.set(data.folderInfo.id, data.folderInfo);
    getFolders(data).forEach(folder => knownFolders.set(folder.id, folder));
    updateOverview();
    updateBreadcrumb();
    renderVisibleMedia();
}
function updateOverview() {
    const counts = { video: 0, image: 0, audio: 0, document: 0 };
    const sizes = { video: 0, image: 0, audio: 0, document: 0 };
    for (const file of getFiles()) {
        const type = fileType(file);
        counts[type]++;
        sizes[type] += Math.max(0, Number(file.fileSize) || 0);
    }
    if (folderData?.counts && Number.isInteger(folderData.page)) {
        for (const type of Object.keys(counts)) { counts[type] = Number(folderData.counts[type] || 0); sizes[type] = Number(folderData.bytes?.[type] || 0); }
    }
    const total = Object.values(sizes).reduce((sum, size) => sum + size, 0);
    setText('count-all', Object.values(counts).reduce((sum, count) => sum + count, 0));
    setText('library-size', formatBytes(total));
    for (const type of fileTypes.filter(item => item !== 'all')) {
        setText('count-' + type, counts[type]);
        setText('stat-' + type, counts[type]);
        setText('size-' + type, formatBytes(sizes[type]));
        document.getElementById('storage-' + type).style.width = (total ? sizes[type] / total * 100 : 0) + '%';
    }
}
function updateBreadcrumb() {
    document.getElementById('welcome-banner').classList[insideFolder ? 'add' : 'remove']('hidden');
    const breadcrumb = document.getElementById('breadcrumb');
    breadcrumb.replaceChildren();
    const root = makeElement('li');
    const home = button('Kho của tôi', '', () => navigateFolder(null));
    if (!insideFolder) home.setAttribute('aria-current', 'page');
    root.appendChild(home);
    breadcrumb.appendChild(root);
    const trail = [];
    const visited = new Set();
    let folder = folderData?.folderInfo;
    while (folder && !visited.has(folder.id)) {
        visited.add(folder.id);
        trail.unshift(folder);
        folder = knownFolders.get(folder.parentFolderId);
    }
    for (const [index, item] of trail.entries()) {
        const segment = makeElement('li');
        segment.appendChild(icon('chevron-right'));
        const current = index === trail.length - 1;
        const label = current ? makeElement('span', 'current', item.folderName) : button(item.folderName, '', () => navigateFolder(item.id));
        if (current) label.setAttribute('aria-current', 'page');
        label.title = item.folderName;
        segment.appendChild(label);
        breadcrumb.appendChild(segment);
    }
    setText('page-title', insideFolder ? (folderData?.folderInfo?.folderName || 'Thư mục của bạn') : 'Kho của tôi');
    setText('page-description', insideFolder ? 'Mọi nội dung trong thư mục này, ở cùng một nơi.' : 'Không gian riêng cho những ý tưởng của bạn.');
}
function emptyState(title, description, symbol, actionLabel, action) {
    const state = makeElement('div', 'empty-state');
    const illustration = makeElement('span', 'empty-illustration');
    illustration.appendChild(icon(symbol));
    state.appendChild(illustration);
    state.appendChild(makeElement('h3', '', title));
    state.appendChild(makeElement('p', '', description));
    if (action) state.appendChild(button(actionLabel, 'button button-secondary', action));
    return state;
}
function renderVisibleMedia() {
    if (!folderData) return;
    const query = normalizeSearch(searchTerm.trim());
    const paged = Number.isInteger(folderData.page);
    const folders = paged ? [...getFolders()] : (activeType === 'all' ? getFolders() : []).filter(folder => normalizeSearch(folder.folderName).includes(query));
    const files = paged ? [...getFiles()] : getFiles().filter(file => (activeType === 'all' || fileType(file) === activeType) && normalizeSearch(file.title).includes(query));
    const byName = (a, b) => String(a || '').localeCompare(String(b || ''), 'vi', { numeric: true, sensitivity: 'base' });
    if (!paged) folders.sort((a, b) => byName(a.folderName, b.folderName));
    if (!paged) files.sort((a, b) => {
        if (sortMode === 'size-desc') return (Number(b.fileSize) || 0) - (Number(a.fileSize) || 0) || byName(a.title, b.title);
        if (sortMode === 'size-asc') return (Number(a.fileSize) || 0) - (Number(b.fileSize) || 0) || byName(a.title, b.title);
        return byName(a.title, b.title) * (sortMode === 'name-desc' ? -1 : 1);
    });
    mediaList.replaceChildren();
    mediaList.setAttribute('aria-busy', 'false');
    mediaList.dataset.view = viewMode;
    setText('visible-count', (folders.length + files.length) + ' mục');
    if (!folders.length && !files.length) {
        const filtering = query || activeType !== 'all';
        mediaList.appendChild(filtering
            ? emptyState('Chưa tìm thấy nội dung phù hợp', 'Thử một tên khác hoặc mở lại tất cả loại tệp trong thư mục này.', 'search', 'Xóa bộ lọc', () => {
                searchTerm = '';
                document.getElementById('file-search').value = '';
                setTypeFilter('all');
            })
            : emptyState('Sẵn sàng cho ý tưởng đầu tiên?', 'Tải lên một tệp hoặc tạo thư mục để bắt đầu không gian của bạn.', 'folder-plus', 'Tải tệp đầu tiên', () => fileInput.click()));
        return;
    }
    if (folders.length) {
        const section = browserSection('Thư mục', folders.length);
        const grid = makeElement('div', 'folder-grid');
        folders.forEach(folder => grid.appendChild(folderCard(folder)));
        section.appendChild(grid);
        mediaList.appendChild(section);
    }
    if (files.length) {
        const section = browserSection(activeType === 'all' ? 'Tệp của bạn' : typeNames[activeType], files.length);
        const grid = makeElement('div', 'file-grid');
        files.forEach(file => grid.appendChild(fileCard(file)));
        section.appendChild(grid);
        mediaList.appendChild(section);
    }
    if (folderData.hasMore) {
        const more = button('Tải thêm', 'button button-secondary', () => { more.disabled = true; loadFolder(currentFolderId, true); });
        mediaList.appendChild(more);
    }
}
function browserSection(title, count) {
    const section = makeElement('section', 'browser-section');
    const heading = makeElement('h3', 'browser-section-title', title);
    heading.appendChild(makeElement('span', '', count));
    section.appendChild(heading);
    return section;
}
function cardMenu(actions, name) {
    const dropdown = makeElement('div', 'dropdown card-menu');
    const toggle = button('', 'icon-button', event => event.stopPropagation());
    toggle.appendChild(icon('more'));
    toggle.dataset.bsToggle = 'dropdown';
    toggle.setAttribute('aria-label', 'Thao tác với ' + name);
    toggle.setAttribute('aria-expanded', 'false');
    const menu = makeElement('ul', 'dropdown-menu dropdown-menu-end');
    for (const [symbol, label, action, danger] of actions) {
        const item = makeElement('li');
        const actionButton = button('', 'dropdown-item' + (danger ? ' text-danger' : ''), event => {
            event.preventDefault();
            event.stopPropagation();
            action(event);
        });
        actionButton.appendChild(icon(symbol));
        actionButton.appendChild(makeElement('span', '', label));
        item.appendChild(actionButton);
        menu.appendChild(item);
    }
    dropdown.appendChild(toggle);
    dropdown.appendChild(menu);
    return dropdown;
}
function folderCard(folder) {
    const card = makeElement('article', 'folder-card');
    const open = button('', 'folder-open', () => navigateFolder(folder.id));
    open.setAttribute('aria-label', 'Mở thư mục ' + folder.folderName);
    const symbol = makeElement('span', 'folder-symbol');
    symbol.appendChild(icon('folder'));
    const copy = makeElement('span', 'folder-copy');
    const name = makeElement('strong', '', folder.folderName);
    name.title = folder.folderName;
    copy.appendChild(name);
    copy.appendChild(makeElement('small', '', 'Thư mục cá nhân'));
    open.appendChild(symbol);
    open.appendChild(copy);
    card.appendChild(open);
    card.appendChild(cardMenu([
        ['edit', 'Đổi tên', event => renameFolder(folder.id, folder.folderName, event)],
        ['trash', 'Xóa thư mục', event => deleteFolder(folder.id, event), true]
    ], folder.folderName));
    return card;
}
function fileCard(file) {
    const type = fileType(file);
    const card = makeElement('article', 'file-card');
    const open = () => type === 'document' ? downloadFile(file) : playMedia(file);
    const preview = button('', 'file-preview preview-' + type, open);
    preview.setAttribute('aria-label', (type === 'document' ? 'Tải về ' : 'Xem ') + file.title);
    const fallback = makeElement('span', 'preview-fallback');
    fallback.appendChild(icon(type === 'audio' ? 'music' : type === 'document' ? 'file' : type));
    preview.appendChild(fallback);
    if (type === 'image' || type === 'video') {
        const media = makeElement(type === 'image' ? 'img' : 'video');
        if (type === 'image') {
            media.alt = '';
            media.loading = 'lazy';
        } else {
            media.preload = 'metadata';
            media.muted = true;
            media.playsInline = true;
            media.setAttribute('aria-hidden', 'true');
        }
        media.addEventListener(type === 'image' ? 'load' : 'loadeddata', () => preview.classList.add('has-preview'));
        media.addEventListener('error', () => { preview.classList.remove('has-preview'); media.remove(); });
        media.src = '/api/files/stream/' + encodeURIComponent(file.id) + (type === 'video' ? '#t=0.5' : '');
        preview.appendChild(media);
    }
    preview.appendChild(makeElement('span', 'file-extension', String(file.extension || 'FILE').toUpperCase()));
    if (type === 'video' || type === 'audio') {
        const play = makeElement('span', 'file-play');
        play.appendChild(icon('play'));
        preview.appendChild(play);
    }
    card.appendChild(preview);
    const detail = makeElement('div', 'file-detail');
    const title = button(file.title, 'file-title', open);
    title.title = file.title;
    const meta = makeElement('div', 'file-meta');
    meta.appendChild(makeElement('span', 'file-info', formatBytes(file.fileSize) + ' · ' + typeNames[type]));
    const download = makeElement('a', 'file-download');
    download.href = '/api/files/download/' + encodeURIComponent(file.id);
    download.setAttribute('aria-label', 'Tải về ' + file.title);
    download.title = 'Tải về';
    download.appendChild(icon('download'));
    download.addEventListener('click', event => { event.preventDefault(); downloadFile(file); });
    meta.appendChild(download);
    detail.appendChild(title);
    detail.appendChild(meta);
    card.appendChild(detail);
    const actions = [['download', 'Tải về', () => downloadFile(file)], ['share', 'Chia sẻ liên kết', () => shareFile(file)], ['edit', 'Đổi tên', event => renameFile(file.id, file.title, event)]];
    if (type === 'video') actions.push(['music', 'Chuyển sang MP3', () => convertToMp3(file)]);
    actions.push(['trash', 'Xóa tệp', event => deleteFile(file.id, event), true]);
    card.appendChild(cardMenu(actions, file.title));
    return card;
}

async function shareFile(file) {
    sharedFile = file;
    shareVersion++;
    setText('share-file-name', file.title);
    setText('share-error', '');
    document.getElementById('share-lifetime').value = '24';
    document.getElementById('share-result').classList.add('hidden');
    document.getElementById('share-url').value = '';
    if (!shareModal) shareModal = new bootstrap.Modal(document.getElementById('share-modal'));
    shareModal.show();
    await loadShareLinks();
}
async function loadShareLinks() {
    const file = sharedFile, version = shareVersion, username = currentUsername;
    if (!file) return;
    const list = document.getElementById('share-links');
    list.replaceChildren(makeElement('p', 'share-hint', 'Đang tải liên kết…'));
    try {
        const links = await requestJson('/api/files/' + encodeURIComponent(file.id) + '/shares');
        if (version !== shareVersion || username !== currentUsername) return;
        list.replaceChildren();
        if (!links.length) list.appendChild(makeElement('p', 'share-hint', 'Chưa có liên kết chia sẻ.'));
        for (const link of links) {
            const row = makeElement('div', 'share-link-row');
            const detail = makeElement('div', 'share-link-detail');
            detail.appendChild(makeElement('span', '', link.status === 'ACTIVE' ? 'Đang hoạt động' : link.status === 'REVOKED' ? 'Đã thu hồi' : 'Đã hết hạn'));
            detail.appendChild(makeElement('small', '', 'Đến ' + new Date(link.expiresAt).toLocaleString('vi-VN')));
            row.appendChild(detail);
            if (link.status === 'ACTIVE') {
                const revoke = button('Thu hồi', 'button button-secondary share-revoke', async () => {
                    revoke.disabled = true;
                    try {
                        await requestJson('/api/files/' + encodeURIComponent(file.id) + '/shares/' + encodeURIComponent(link.id), { method: 'DELETE' });
                        if (version !== shareVersion || username !== currentUsername) return;
                        if (document.getElementById('share-result').dataset.shareId === link.id) { document.getElementById('share-result').classList.add('hidden'); document.getElementById('share-url').value = ''; }
                        showToast('Đã thu hồi liên kết.'); await loadShareLinks();
                    } catch (error) { if (version === shareVersion) { setText('share-error', error.message); revoke.disabled = false; } }
                });
                row.appendChild(revoke);
            }
            list.appendChild(row);
        }
    } catch (error) { if (version === shareVersion && username === currentUsername) { list.replaceChildren(); setText('share-error', error.message); } }
}
document.getElementById('share-create').addEventListener('click', async () => {
    const file = sharedFile, version = shareVersion, username = currentUsername;
    const submit = document.getElementById('share-create');
    if (!file || submit.disabled) return;
    submit.disabled = true; setText('share-error', '');
    try {
        const created = await requestJson('/api/files/' + encodeURIComponent(file.id) + '/shares', jsonOptions('POST', { expiresInHours: Number(document.getElementById('share-lifetime').value) }));
        if (version !== shareVersion || username !== currentUsername) return;
        document.getElementById('share-url').value = new URL(created.path, location.origin).href;
        document.getElementById('share-result').dataset.shareId = created.id;
        document.getElementById('share-result').classList.remove('hidden');
        setText('share-created-expiry', 'Có hiệu lực đến ' + new Date(created.expiresAt).toLocaleString('vi-VN'));
        await loadShareLinks();
    } catch (error) { if (version === shareVersion && username === currentUsername) setText('share-error', error.message); }
    finally { submit.disabled = false; }
});
document.getElementById('share-copy').addEventListener('click', async () => {
    const input = document.getElementById('share-url');
    if (!input.value) return;
    try {
        if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) await navigator.clipboard.writeText(input.value);
        else { input.focus(); input.select(); if (!document.execCommand?.('copy')) { showToast('Liên kết đã được chọn. Nhấn Ctrl+C để sao chép.', 'info'); return; } }
        showToast('Đã sao chép liên kết.');
    } catch (error) { input.focus(); input.select(); showToast('Hãy sao chép liên kết đang được chọn.', 'info'); }
});
document.getElementById('share-modal').addEventListener('hidden.bs.modal', () => { shareVersion++; sharedFile = null; document.getElementById('share-url').value = ''; document.getElementById('share-result').classList.add('hidden'); });

function openActionDialog(options) {
    dialogState = options;
    setText('action-title', options.title);
    setText('action-description', options.description || '');
    setText('action-label', options.label || 'Tên');
    setText('action-error', '');
    const input = document.getElementById('action-input');
    input.value = options.value || '';
    input.type = options.inputType || 'text';
    input.placeholder = options.placeholder || '';
    input.maxLength = options.maxLength || 255;
    input.required = !options.confirm;
    input.disabled = false;
    document.getElementById('action-field').classList[options.confirm ? 'add' : 'remove']('hidden');
    const symbol = document.getElementById('action-icon');
    symbol.className = 'dialog-icon' + (options.danger ? ' is-danger' : '');
    symbol.replaceChildren(icon(options.icon || 'folder-plus'));
    const submit = document.getElementById('action-submit');
    submit.className = 'button ' + (options.danger ? 'button-danger' : 'button-primary');
    submit.textContent = options.submit || 'Lưu thay đổi';
    submit.disabled = true;
    if (!actionModal) actionModal = new bootstrap.Modal(document.getElementById('action-modal'));
    actionModal.show();
}
document.getElementById('action-modal').addEventListener('shown.bs.modal', () => {
    document.getElementById('action-submit').disabled = false;
    const input = document.getElementById('action-input');
    if (dialogState && !dialogState.confirm) { input.focus(); input.select(); }
    else document.getElementById('action-submit').focus();
});
document.getElementById('action-modal').addEventListener('hidden.bs.modal', () => { dialogState = null; });
document.getElementById('action-form').addEventListener('submit', async event => {
    event.preventDefault();
    const state = dialogState;
    const submit = document.getElementById('action-submit');
    if (!state || submit.disabled) return;
    const value = document.getElementById('action-input').value.trim();
    if (!state.confirm && !value) { setText('action-error', 'Vui lòng điền thông tin trước khi tiếp tục.'); return; }
    submit.disabled = true;
    submit.textContent = 'Đang xử lý…';
    setText('action-error', '');
    try {
        await state.onSubmit(value);
        if (dialogState === state) actionModal.hide();
    } catch (error) { if (dialogState === state) setText('action-error', error.message); }
    finally {
        if (dialogState === state) {
            submit.disabled = false;
            submit.textContent = state.submit || 'Lưu thay đổi';
        }
    }
});
function createFolder() {
    const parentId = currentFolderId;
    openActionDialog({
        title: 'Một nơi cho ý tưởng mới', description: 'Đặt tên thư mục để sắp xếp nội dung theo cách của bạn.',
        label: 'Tên thư mục', placeholder: 'Ví dụ: Dự án mùa thu', submit: 'Tạo thư mục', icon: 'folder-plus',
        async onSubmit(name) {
            await requestJson('/api/folders', jsonOptions('POST', { folderName: name, parentId }));
            showToast('Đã tạo thư mục ' + name + '.');
            if (currentFolderId === parentId) loadFolder(parentId);
        }
    });
}
function renameFolder(id, name, event) {
    event?.stopPropagation();
    openActionDialog({
        title: 'Đổi tên thư mục', description: 'Một cái tên mới, nội dung vẫn ở nguyên đây.', label: 'Tên thư mục', value: name, icon: 'edit',
        async onSubmit(value) {
            await requestJson('/api/folders/' + encodeURIComponent(id), jsonOptions('PUT', { folderName: value }));
            showToast('Đã đổi tên thư mục.');
            loadFolder(currentFolderId);
        }
    });
}
function renameFile(id, name, event) {
    event?.stopPropagation();
    openActionDialog({
        title: 'Đổi tên tệp', description: 'Đặt tên dễ nhớ để tìm lại nội dung nhanh hơn.', label: 'Tên tệp', value: name, icon: 'edit',
        async onSubmit(value) {
            await requestJson('/api/files/' + encodeURIComponent(id), jsonOptions('PUT', { title: value }));
            showToast('Đã đổi tên tệp.');
            loadFolder(currentFolderId);
        }
    });
}
function deleteFolder(id, event) {
    event?.stopPropagation();
    openActionDialog({
        title: 'Xóa thư mục này?', description: 'Chỉ có thể xóa thư mục trống. Thao tác này không thể hoàn tác.',
        confirm: true, danger: true, submit: 'Xóa thư mục', icon: 'trash',
        async onSubmit() {
            await requestJson('/api/folders/' + encodeURIComponent(id), { method: 'DELETE' }, 'Thư mục cần trống trước khi xóa. Hãy xóa các tệp và thư mục con trước.');
            showToast('Đã xóa thư mục.');
            loadFolder(currentFolderId);
        }
    });
}
function deleteFile(id, event) {
    event?.stopPropagation();
    openActionDialog({
        title: 'Xóa tệp này?', description: 'Tệp sẽ được xóa khỏi không gian của bạn. Thao tác này không thể hoàn tác.',
        confirm: true, danger: true, submit: 'Xóa tệp', icon: 'trash',
        async onSubmit() {
            const result = await requestJson('/api/files/' + encodeURIComponent(id), { method: 'DELETE' });
            showToast(result.status === 'DELETING' ? result.message : 'Đã xóa tệp.');
            loadFolder(currentFolderId);
        }
    });
}
function importYouTube() {
    const folderId = currentFolderId;
    const keys = new Map();
    closeSidebar();
    openActionDialog({
        title: 'Mang cảm hứng về đây', description: 'Dán đường dẫn YouTube. Video sẽ được lưu vào thư mục đang mở.',
        label: 'Đường dẫn YouTube', placeholder: 'https://www.youtube.com/watch?v=…', submit: 'Lưu video', icon: 'youtube', inputType: 'url', maxLength: 2048,
        async onSubmit(url) {
            if (!keys.has(url)) keys.set(url, newRequestId());
            const result = await requestJson('/api/files/youtube', idempotentOptions({ url, folderId }, keys.get(url)));
            handleRealtimeProgress({ taskId: result.jobId, taskName: 'Tải video YouTube', status: result.status || 'PENDING_DISPATCH' });
            showToast('Video đã được thêm vào hàng đợi xử lý.');
        }
    });
}
async function convertToMp3(file) {
    const keyName = 'cloudmedia-convert:' + currentUsername + ':' + file.id;
    const key = localStorage.getItem(keyName) || newRequestId(); localStorage.setItem(keyName, key);
    try {
        const result = await requestJson('/api/media/convert-to-mp3', idempotentOptions({ fileId: file.id }, key));
        localStorage.removeItem(keyName);
        handleRealtimeProgress({ taskId: result.jobId, taskName: 'Chuyển MP3: ' + file.title, status: 'QUEUED' });
        showToast('Đã thêm tác vụ chuyển MP3.');
    } catch (error) { showToast(error.message, 'error'); }
}
async function downloadFile(file) {
    try {
        const response = await fetchWithAuth('/api/folders', { method: 'GET' });
        if (!response.ok) throw new Error('Không thể tải tệp lúc này.');
        const link = makeElement('a');
        link.href = '/api/files/download/' + encodeURIComponent(file.id);
        link.download = file.title;
        document.body.appendChild(link);
        link.click();
        link.remove();
    } catch (error) { showToast(error.message, 'error'); }
}
async function playMedia(file) {
    try {
        const response = await fetchWithAuth('/api/folders', { method: 'GET' });
        if (!response.ok) throw new Error('Không thể mở nội dung lúc này.');
        const type = fileType(file);
        const content = document.getElementById('media-content');
        content.replaceChildren();
        setText('media-title', file.title);
        const media = makeElement(type === 'image' ? 'img' : type === 'audio' ? 'audio' : 'video');
        if (type === 'image') media.alt = file.title;
        else { media.controls = true; media.autoplay = true; }
        media.src = '/api/files/stream/' + encodeURIComponent(file.id);
        media.addEventListener('error', () => showToast('Trình duyệt chưa phát được định dạng này. Bạn có thể tải tệp về để xem.', 'info'));
        content.appendChild(media);
        currentPreview = media;
        if (!previewModal) previewModal = new bootstrap.Modal(document.getElementById('media-modal'));
        previewModal.show();
    } catch (error) { showToast(error.message, 'error'); }
}
document.getElementById('media-modal').addEventListener('hidden.bs.modal', () => {
    currentPreview?.pause?.();
    currentPreview = null;
    document.getElementById('media-content').replaceChildren();
});

function toggleAuth(register) {
    loginSection.classList[register ? 'add' : 'remove']('hidden');
    registerSection.classList[register ? 'remove' : 'add']('hidden');
    setText('auth-title', register ? 'Một không gian mới cho bạn.' : 'Cảm hứng bắt đầu ở đây.');
    setText('auth-subtitle', register ? 'Tạo tài khoản và bắt đầu lưu giữ điều bạn yêu thích.' : 'Đăng nhập để mở không gian sáng tạo của bạn.');
    authMessage.textContent = '';
    document.getElementById(register ? 'reg-username' : 'login-username').focus();
}
function authStatus(message, kind = 'danger') {
    authMessage.textContent = message;
    authMessage.className = 'auth-message text-' + kind;
}
document.getElementById('linkToRegister').addEventListener('click', () => toggleAuth(true));
document.getElementById('linkToLogin').addEventListener('click', () => toggleAuth(false));
document.getElementById('toggle-password').addEventListener('click', () => {
    const input = document.getElementById('login-password');
    const visible = input.type === 'password';
    input.type = visible ? 'text' : 'password';
    const toggle = document.getElementById('toggle-password');
    toggle.setAttribute('aria-pressed', String(visible));
    toggle.setAttribute('aria-label', visible ? 'Ẩn mật khẩu' : 'Hiện mật khẩu');
});
loginSection.addEventListener('submit', async event => {
    event.preventDefault();
    const user = document.getElementById('login-username').value.trim();
    const pass = document.getElementById('login-password').value;
    if (!user || !pass) { authStatus('Vui lòng nhập tên đăng nhập và mật khẩu.'); return; }
    const submit = document.getElementById('btnLogin');
    if (submit.disabled) return;
    const original = submit.innerHTML;
    submit.disabled = true;
    submit.textContent = 'Đang đăng nhập…';
    authStatus('Đang mở không gian của bạn…', 'secondary');
    try {
        const response = await fetch('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: user, password: pass }), credentials: 'include' });
        const data = await response.json();
        if (!response.ok) throw new Error(data.error || data.message || 'Sai tên đăng nhập hoặc mật khẩu.');
        localStorage.setItem('username', user);
        currentUsername = user;
        authMessage.textContent = '';
        document.getElementById('login-password').value = '';
        showMainApp();
    } catch (error) { authStatus(error.message || 'Không thể kết nối máy chủ.'); }
    finally { submit.disabled = false; submit.innerHTML = original; }
});
registerSection.addEventListener('submit', async event => {
    event.preventDefault();
    const user = document.getElementById('reg-username').value.trim();
    const pass = document.getElementById('reg-password').value;
    const confirmPass = document.getElementById('reg-confirm-password').value;
    if (!user || !pass || !confirmPass) { authStatus('Vui lòng điền đầy đủ thông tin.'); return; }
    if (user.length > 50 || pass.length < 8 || new TextEncoder().encode(pass).length > 72) { authStatus('Tên đăng nhập tối đa 50 ký tự; mật khẩu từ 8 ký tự và tối đa 72 byte UTF-8.'); return; }
    if (pass !== confirmPass) { authStatus('Mật khẩu xác nhận chưa khớp.'); return; }
    const submit = document.getElementById('btnSubmitRegister');
    if (submit.disabled) return;
    const original = submit.innerHTML;
    submit.disabled = true;
    submit.textContent = 'Đang tạo tài khoản…';
    authStatus('Đang chuẩn bị không gian mới…', 'secondary');
    try {
        const response = await fetch('/api/auth/register', jsonOptions('POST', { username: user, password: pass }));
        const data = await response.json();
        if (!response.ok) throw new Error(data.error || data.message || 'Không thể tạo tài khoản.');
        document.getElementById('reg-username').value = '';
        document.getElementById('reg-password').value = '';
        document.getElementById('reg-confirm-password').value = '';
        toggleAuth(false);
        document.getElementById('login-username').value = user;
        authStatus('Tài khoản đã sẵn sàng. Đăng nhập để bắt đầu!', 'success');
    } catch (error) { authStatus(error.message || 'Không thể kết nối máy chủ.'); }
    finally { submit.disabled = false; submit.innerHTML = original; }
});
document.getElementById('btnLogout').addEventListener('click', async () => {
    disconnectWebSocket();
    clearTimeout(jobsPollTimer); clearTimeout(searchTimer);
    folderLoadVersion++;
    folderData = null;
    folderLoading = false;
    currentFolderId = null;
    knownFolders.clear();
    localStorage.removeItem('username');
    currentUsername = null;
    closeSidebar();
    appContainer.classList.add('hidden');
    authContainer.classList.remove('hidden');
    actionModal?.hide();
    previewModal?.hide();
    shareModal?.hide(); shareVersion++; sharedFile = null; document.getElementById('share-url').value = '';
    dialogState = null;
    document.getElementById('login-username').value = '';
    document.getElementById('login-password').value = '';
    toggleAuth(false);
    for (const task of progressRows.values()) clearTimeout(task.removeTimer);
    document.getElementById('progress-list').replaceChildren();
    progressRows.clear();
    document.getElementById('progress-container').classList.add('hidden');
    searchTerm = '';
    document.getElementById('file-search').value = '';
    setTypeFilter('all');
    try { await fetch('/api/auth/logout', { method: 'POST', credentials: 'include' }); } catch (error) {}
});

document.getElementById('btnCreateFolder').addEventListener('click', createFolder);
document.getElementById('btnUploadLocal').addEventListener('click', () => fileInput.click());
document.getElementById('btnHeroUpload').addEventListener('click', () => fileInput.click());
document.getElementById('btnBrowseFiles').addEventListener('click', () => fileInput.click());
document.getElementById('btnUploadYoutube').addEventListener('click', importYouTube);
document.getElementById('btnYoutubeShortcut').addEventListener('click', importYouTube);
document.getElementById('btnHome').addEventListener('click', () => navigateFolder(null));
for (const type of fileTypes) {
    document.getElementById('nav-' + type).addEventListener('click', () => setTypeFilter(type));
    document.getElementById('filter-' + type).addEventListener('click', () => setTypeFilter(type));
    if (type !== 'all') document.getElementById('stat-button-' + type).addEventListener('click', () => setTypeFilter(type));
}
document.getElementById('file-search').addEventListener('input', event => { searchTerm = event.target.value; clearTimeout(searchTimer); if (Number.isInteger(folderData?.page)) searchTimer = setTimeout(() => loadFolder(currentFolderId), 300); else if (!folderLoading) renderVisibleMedia(); });
document.getElementById('file-sort').addEventListener('change', event => { sortMode = event.target.value; if (Number.isInteger(folderData?.page)) loadFolder(currentFolderId); else if (!folderLoading) renderVisibleMedia(); });
document.getElementById('btnGridView').addEventListener('click', () => setView('grid'));
document.getElementById('btnListView').addEventListener('click', () => setView('list'));
document.getElementById('sidebar-toggle').addEventListener('click', () => {
    const open = !appContainer.classList.contains('sidebar-open');
    appContainer.classList[open ? 'add' : 'remove']('sidebar-open');
    document.getElementById('sidebar-toggle').setAttribute('aria-expanded', String(open));
});
document.getElementById('sidebar-backdrop').addEventListener('click', closeSidebar);
document.addEventListener('keydown', event => {
    if (event.key === 'Escape') closeSidebar();
    if (event.key === '/' && currentUsername && !['INPUT', 'TEXTAREA', 'SELECT'].includes(event.target.tagName) && !event.target.isContentEditable && !document.body.classList.contains('modal-open')) {
        event.preventDefault();
        document.getElementById('file-search').focus();
    }
});

async function uploadFile(file) {
    if (!file) return;
    if (uploadInProgress) { showToast('Một tệp đang được tải lên. Vui lòng chờ hoàn tất.', 'info'); return; }
    uploadInProgress = true;
    const originalText = btnUploadDropdown.innerHTML;
    const uploadFolderId = currentFolderId;
    const uploadUser = currentUsername;
    const chunkSize = 5 * 1024 * 1024;
    const totalChunks = Math.max(1, Math.ceil(file.size / chunkSize));
    const resumeKey = 'cloudmedia-upload:' + JSON.stringify([uploadUser, uploadFolderId, file.name, file.size, file.lastModified || 0]);
    let fileGuid = localStorage.getItem(resumeKey);
    if (!uuidPattern.test(fileGuid || '')) fileGuid = newRequestId();
    localStorage.setItem(resumeKey, fileGuid);
    let taskId = 'upload-' + fileGuid;
    const taskName = 'Tải tệp: ' + file.name;
    btnUploadDropdown.textContent = 'Đang tải lên…';
    const uploadButtons = [btnUploadDropdown, ...['btnHeroUpload', 'btnBrowseFiles', 'btnUploadLocal'].map(id => document.getElementById(id))];
    uploadButtons.forEach(node => { node.disabled = true; });
    try {
        if (totalChunks > 10000) throw new Error('Tệp vượt quá giới hạn 10000 mảnh.');
        const initialize = () => requestJson('/api/files/uploads', jsonOptions('POST', { uploadId: fileGuid, filename: file.name, size: file.size, totalChunks, folderId: uploadFolderId }));
        let session;
        try { session = await initialize(); }
        catch (error) {
            if (error.status !== 410) throw error;
            fileGuid = newRequestId(); taskId = 'upload-' + fileGuid; localStorage.setItem(resumeKey, fileGuid); session = await initialize();
        }
        const previous = progressRows.get(taskId);
        if (previous) { clearTimeout(previous.removeTimer); previous.row.remove(); progressRows.delete(taskId); }
        const received = new Set(session.receivedChunks || []);
        if (received.size) showToast('Tiếp tục tải ' + file.name + ' từ phần đã lưu.', 'info');
        for (let chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
            if (currentUsername !== uploadUser) throw new Error('Phiên tải lên đã kết thúc.');
            if (session.status === 'COMPLETED' || received.has(chunkIndex)) continue;
            const formData = new FormData();
            formData.append('file', file.slice(chunkIndex * chunkSize, (chunkIndex + 1) * chunkSize), 'chunk');
            formData.append('fileGuid', fileGuid);
            formData.append('chunkIndex', chunkIndex);
            formData.append('totalChunks', totalChunks);
            const response = await fetchWithAuth('/api/files/upload-chunk', { method: 'POST', body: formData });
            if (!response.ok) {
                const data = await response.json().catch(() => ({}));
                throw new Error(data.error || 'Không thể lưu mảnh tệp.');
            }
            handleRealtimeProgress({ taskId, taskName, progress: Math.round((chunkIndex + 1) / totalChunks * 95), status: 'PROCESSING' });
        }
        if (currentUsername !== uploadUser) throw new Error('Phiên tải lên đã kết thúc.');
        await requestJson('/api/files/merge-chunks', jsonOptions('POST', { fileGuid, originalFilename: file.name, totalChunks, folderId: uploadFolderId }), 'Không thể gộp tệp.');
        localStorage.removeItem(resumeKey);
        if (currentUsername === uploadUser) handleRealtimeProgress({ taskId, taskName, progress: 100, status: 'COMPLETED' });
    } catch (error) {
        if (currentUsername === uploadUser) handleRealtimeProgress({ taskId, taskName, progress: 0, status: 'FAILED', message: error.message + ' Chọn lại cùng tệp để tiếp tục.' });
    } finally {
        uploadInProgress = false;
        btnUploadDropdown.innerHTML = originalText;
        uploadButtons.forEach(node => { node.disabled = false; });
        fileInput.value = '';
    }
}
fileInput.addEventListener('change', event => uploadFile(event.target.files[0]));
const dropZone = document.getElementById('drop-zone');
let dragDepth = 0;
dropZone.addEventListener('dragenter', event => { event.preventDefault(); dragDepth++; dropZone.classList.add('is-dragging'); });
dropZone.addEventListener('dragover', event => { event.preventDefault(); event.dataTransfer.dropEffect = 'copy'; });
dropZone.addEventListener('dragleave', event => { event.preventDefault(); if (--dragDepth <= 0) { dragDepth = 0; dropZone.classList.remove('is-dragging'); } });
dropZone.addEventListener('drop', event => {
    event.preventDefault();
    dragDepth = 0;
    dropZone.classList.remove('is-dragging');
    const files = event.dataTransfer.files;
    if (files.length !== 1) { showToast('Vui lòng chọn một tệp cho mỗi lần tải lên.', 'info'); return; }
    uploadFile(files[0]);
});

setView(viewMode);
document.getElementById('file-sort').value = sortMode;
if (currentUsername) showMainApp();
