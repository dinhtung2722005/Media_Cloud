// ==========================================
// 1. KHAI BÁO BIẾN TOÀN CỤC & DOM SELECTORS
// ==========================================
const authContainer = document.getElementById('auth-container');
const appContainer = document.getElementById('app-container');
const authMessage = document.getElementById('auth-message');
const welcomeText = document.getElementById('welcome-text');
const mediaList = document.getElementById('media-list');
const loginSection = document.getElementById('login-section');
const registerSection = document.getElementById('register-section');
const btnUploadDropdown = document.getElementById('btnUploadDropdown');
let currentUsername = localStorage.getItem('username');
let currentFolderId = null;
let stompClient = null;
let refreshPromise = null;
let reconnectTimer = null;
let webSocketGeneration = 0;
const progressRows = new Map();
// Khởi tạo thẻ file ẩn duy nhất 1 lần
const fileInput = document.createElement('input');
fileInput.type = 'file';
fileInput.style.display = 'none';
document.body.appendChild(fileInput);

if (currentUsername) {
    showMainApp();
}

// ==========================================
// 2. HÀM INTERCEPTOR: XỬ LÝ LÀM MỚI TOKEN NGẦM
// ==========================================
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

// ==========================================
// 3. CÁC HÀM XỬ LÝ GIAO DIỆN & LOGIC CHÍNH
// ==========================================
function showMainApp() {
    authContainer.classList.add('hidden');
    appContainer.classList.remove('hidden');
    welcomeText.innerText = "Xin chào, " + currentUsername;
    connectWebSocket(currentUsername);
    loadFolder(null); 
}

async function connectWebSocket(username) {
    const generation = ++webSocketGeneration;
    const retry = () => {
        if (generation === webSocketGeneration && currentUsername === username) {
            reconnectTimer = setTimeout(() => connectWebSocket(username), 5000);
        }
    };
    try {
        // Renew an expired cookie before the authenticated WebSocket handshake.
        const response = await fetchWithAuth('/api/folders');
        if (!response.ok) throw new Error('Không thể xác thực WebSocket');
        if (generation !== webSocketGeneration || currentUsername !== username) return;
        const client = Stomp.over(new SockJS('/ws'));
        stompClient = client;
        client.debug = null;
        client.connect({}, () => {
            if (generation !== webSocketGeneration) { client.disconnect(); return; }
            client.subscribe('/user/queue/progress', response => {
                if (generation === webSocketGeneration) handleRealtimeProgress(JSON.parse(response.body));
            });
        }, retry);
    } catch (error) {
        retry();
    }
}

function disconnectWebSocket() {
    webSocketGeneration++;
    clearTimeout(reconnectTimer);
    if (stompClient !== null) {
        if (stompClient.connected) stompClient.disconnect();
        else if (stompClient.ws) stompClient.ws.close();
        stompClient = null;
    }
    console.log("Đã ngắt kết nối WebSocket");
}

function makeElement(tag, className = '', text = '') {
    const node = document.createElement(tag);
    node.className = className;
    node.textContent = text;
    return node;
}

function handleRealtimeProgress(data) {
    const container = document.getElementById('progress-container');
    const list = document.getElementById('progress-list');
    if (!container || !list) return;
    let value = Number(data.progress || 0);
    if (data.progressLine) {
        const match = data.progressLine.match(/([\d.]+)%/);
        if (!match) return;
        value = Number(match[1]);
    }
    const progress = Number.isFinite(value) ? Math.max(0, Math.min(100, Math.round(value))) : 0;
    const taskId = String(data.taskId || 'default-task');
    const taskName = String(data.taskName || 'Đang xử lý...');
    container.classList.remove('hidden');
    let task = progressRows.get(taskId);
    if (!task) {
        const row = makeElement('div', 'col-12 p-2 border rounded bg-white shadow-sm mb-2');
        const heading = makeElement('div', 'd-flex justify-content-between mb-1 small');
        const name = makeElement('span', 'fw-bold text-truncate', taskName);
        name.style.maxWidth = '80%';
        const percent = makeElement('span', 'text-primary fw-bold');
        const track = makeElement('div', 'progress');
        track.style.height = '8px';
        const bar = makeElement('div', 'progress-bar progress-bar-striped progress-bar-animated bg-primary');
        heading.appendChild(name);
        heading.appendChild(percent);
        track.appendChild(bar);
        row.appendChild(heading);
        row.appendChild(track);
        list.appendChild(row);
        task = { row, name, percent, bar };
        progressRows.set(taskId, task);
    }
    task.name.textContent = taskName;
    task.percent.textContent = progress + '%';
    task.bar.style.width = progress + '%';
    if (data.status === 'COMPLETED' || data.status === 'FAILED' || data.status === 'CANCELLED') {
        const success = data.status === 'COMPLETED';
        task.bar.className = 'progress-bar ' + (success ? 'bg-success' : 'bg-danger');
        task.percent.textContent = success ? 'Xong!' : data.status === 'CANCELLED' ? 'Đã hủy' : 'Lỗi!';
        if (success) task.bar.style.width = '100%';
        showToast(success ? '✅ ' + taskName + ' thành công!' : '❌ ' + (data.message || 'Tác vụ thất bại'), success ? 'bg-success' : 'bg-danger');
        setTimeout(() => {
            task.row.remove();
            progressRows.delete(taskId);
            if (!list.children.length) container.classList.add('hidden');
        }, success ? 3000 : 5000);
        if (success) loadFolder(currentFolderId);
    }
}

function showToast(message, bgClass = 'bg-success') {
    const toastEl = document.getElementById('realtimeToast');
    const toastMsg = document.getElementById('toast-message');
    if (!toastEl || !toastMsg) return;

    toastEl.className = `toast align-items-center border-0 text-white ${bgClass}`;
    toastMsg.innerText = message;

    const toast = new bootstrap.Toast(toastEl);
    toast.show();
}

async function loadFolder(folderId) {
    currentFolderId = folderId;
    mediaList.innerHTML = '<div class="col-12 text-center text-muted py-5">Đang tải dữ liệu...</div>';
    let url = folderId ? `/api/folders/${folderId}` : '/api/folders';

    try {
        // Dùng fetchWithAuth thay vì fetch
        const response = await fetchWithAuth(url, { method: 'GET' });

        if (response.ok) {
            const data = await response.json();
            renderMedia(data, folderId);
        }
    } catch (error) {
        mediaList.innerHTML = '<div class="col-12 text-danger text-center">Lỗi mạng kết nối!</div>';
    }
}

function cardMenu(actions) {
    const dropdown = makeElement('div', 'dropdown position-absolute top-0 end-0 m-1');
    const toggle = makeElement('button', 'btn btn-link text-secondary fs-4', '⋮');
    toggle.type = 'button';
    toggle.dataset.bsToggle = 'dropdown';
    toggle.addEventListener('click', event => event.stopPropagation());
    const menu = makeElement('ul', 'dropdown-menu dropdown-menu-end shadow');
    for (const [label, action, danger] of actions) {
        const item = makeElement('li');
        const button = makeElement('button', 'dropdown-item' + (danger ? ' text-danger' : ''), label);
        button.type = 'button';
        button.addEventListener('click', event => {
            event.preventDefault();
            event.stopPropagation();
            action(event);
        });
        item.appendChild(button);
        menu.appendChild(item);
    }
    dropdown.appendChild(toggle);
    dropdown.appendChild(menu);
    return dropdown;
}

function renderMedia(data, isInsideFolder) {
    mediaList.replaceChildren();
    const folders = isInsideFolder ? (data.subFolders || data.folders || []) : (Array.isArray(data) ? data : data.folders || []);
    const files = data.files || [];
    if (!folders.length && !files.length) {
        mediaList.appendChild(makeElement('div', 'col-12 text-center text-muted py-5', 'Thư mục trống'));
        return;
    }
    folders.forEach(folder => {
        const column = makeElement('div', 'col-md-3 col-sm-6 mb-3');
        const card = makeElement('div', 'card shadow-sm text-center p-3 position-relative');
        card.appendChild(cardMenu([
            ['✏️ Sửa tên', event => renameFolder(folder.id, folder.folderName, event)],
            ['🔗 Chia sẻ', event => shareItem(folder.id, 'folder', event)],
            ['❌ Xóa thư mục', event => deleteFolder(folder.id, event), true]
        ]));
        const content = makeElement('div');
        content.style.cursor = 'pointer';
        content.addEventListener('click', () => loadFolder(folder.id));
        content.appendChild(makeElement('h1', 'text-warning mb-0 display-4', '📁'));
        content.appendChild(makeElement('p', 'mb-0 text-truncate fw-bold mt-2', folder.folderName));
        card.appendChild(content);
        column.appendChild(card);
        mediaList.appendChild(column);
    });
    files.forEach(file => {
        const column = makeElement('div', 'col-md-3 col-sm-6 mb-3');
        const card = makeElement('div', 'card shadow-sm text-center p-3 position-relative h-100 d-flex flex-column');
        card.appendChild(cardMenu([
            ['✏️ Sửa tên', event => renameFile(file.id, file.title, event)],
            ['🔗 Chia sẻ', event => shareItem(file.id, 'file', event)],
            ['❌ Xóa file', event => deleteFile(file.id, event), true]
        ]));
        const content = makeElement('div');
        const ext = String(file.extension || '').toLowerCase();
        const video = ['mp4', 'webm', 'ogg', 'mov'].includes(ext);
        const image = ['jpg', 'jpeg', 'png', 'gif', 'webp'].includes(ext);
        if (video || image) {
            const preview = makeElement(image ? 'img' : 'video', 'rounded shadow-sm bg-dark');
            preview.src = '/api/files/stream/' + encodeURIComponent(file.id) + (video ? '#t=1.0' : '');
            preview.style.cssText = 'width:100%;height:100px;object-fit:cover;';
            if (video) preview.preload = 'metadata';
            else preview.alt = file.title;
            content.appendChild(preview);
        } else {
            content.appendChild(makeElement('h1', 'text-primary mb-0 display-4', '📄'));
        }
        if (video) {
            content.style.cursor = 'pointer';
            content.addEventListener('click', () => playVideo(file.id, file.title));
        }
        card.appendChild(content);
        const title = makeElement('p', 'mb-0 text-truncate fw-bold mt-3', file.title);
        title.title = file.title;
        card.appendChild(title);
        const download = makeElement('a', 'btn btn-sm btn-outline-secondary w-100 mt-auto', 'Tải về');
        download.href = '/api/files/download/' + encodeURIComponent(file.id);
        card.appendChild(download);
        column.appendChild(card);
        mediaList.appendChild(column);
    });
}

// Dùng fetchWithAuth cho các thao tác Xóa, Sửa, Thêm
async function deleteFolder(folderId, event) {
    event.stopPropagation(); 
    if (!confirm("Bạn có chắc chắn muốn xóa thư mục này và TOÀN BỘ file bên trong không?")) return;
    try {
        const response = await fetchWithAuth(`/api/folders/${folderId}`, { method: 'DELETE' });
        if (response.ok) loadFolder(currentFolderId);
        else alert("Lỗi xóa thư mục!");
    } catch (error) { alert("Lỗi kết nối máy chủ!"); }
}

async function deleteFile(fileId, event) {
    event.stopPropagation();
    if (!confirm("Bạn có chắc chắn muốn xóa tệp này không?")) return;
    try {
        const response = await fetchWithAuth(`/api/files/${fileId}`, { method: 'DELETE' });
        if (response.ok) loadFolder(currentFolderId);
        else alert("Lỗi xóa file!");
    } catch (error) { alert("Lỗi kết nối máy chủ!"); }
}

async function renameFolder(folderId, currentName, event) {
    event.stopPropagation();
    const newName = prompt("Đổi tên thư mục:", currentName);
    if (!newName || newName.trim() === "" || newName.trim() === currentName) return;
    try {
        const response = await fetchWithAuth(`/api/folders/${folderId}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ folderName: newName.trim() })
        });
        if (response.ok) loadFolder(currentFolderId);
        else alert("Lỗi đổi tên!");
    } catch (error) { alert("Lỗi kết nối máy chủ!"); }
}

async function renameFile(fileId, currentName, event) {
    event.stopPropagation();
    const newName = prompt("Đổi tên tệp:", currentName);
    if (!newName || newName.trim() === "" || newName.trim() === currentName) return;
    try {
        const response = await fetchWithAuth(`/api/files/${fileId}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ title: newName.trim() })
        });
        if (response.ok) loadFolder(currentFolderId);
        else alert("Lỗi đổi tên tệp!");
    } catch (error) { alert("Lỗi kết nối máy chủ!"); }
}

function shareItem(id, type, event) {
    event.stopPropagation();
    alert(`Tính năng tạo liên kết chia sẻ cho ${type === 'folder' ? 'thư mục' : 'tệp tin'} đang được phát triển!\nID: ${id}`);
}

// Preflight Check trước khi phát video để đảm bảo Cookie còn hiệu lực
async function playVideo(fileId, fileName) {
    // Ping nhẹ API để làm mới Token ngầm (nếu hết hạn) trước khi mở Video
    try { await fetchWithAuth('/api/folders', { method: 'GET' }); } catch (e) { return; }

    const overlay = document.createElement('div');
    overlay.id = 'video-player-overlay';
    overlay.style.cssText = 'position: fixed; top: 0; left: 0; width: 100vw; height: 100vh; background: rgba(0,0,0,0.9); z-index: 9999; display: flex; flex-direction: column; align-items: center; justify-content: center;';

    const closeBtn = document.createElement('button');
    closeBtn.innerHTML = '&times; Đóng Video';
    closeBtn.className = 'btn btn-danger mb-3';
    closeBtn.onclick = () => document.body.removeChild(overlay); 

    const title = document.createElement('h4');
    title.innerText = fileName;
    title.className = 'text-white mb-3';

    const video = document.createElement('video');
    video.src = `/api/files/stream/${fileId}`; 
    video.controls = true; 
    video.autoplay = true; 
    video.style.cssText = 'max-width: 90%; max-height: 75%; border-radius: 8px; box-shadow: 0 4px 15px rgba(0,0,0,0.5); background: black;';

    overlay.appendChild(closeBtn);
    overlay.appendChild(title);
    overlay.appendChild(video);
    document.body.appendChild(overlay);
}

// ==========================================
// 4. QUẢN LÝ TÀI KHOẢN (LOGIN/LOGOUT/REGISTER)
// ==========================================
document.getElementById('linkToRegister')?.addEventListener('click', (e) => {
    e.preventDefault();
    loginSection.classList.add('hidden');
    registerSection.classList.remove('hidden');
    authMessage.innerText = ''; 
});

document.getElementById('linkToLogin')?.addEventListener('click', (e) => {
    e.preventDefault();
    registerSection.classList.add('hidden');
    loginSection.classList.remove('hidden');
    authMessage.innerText = '';
});

document.getElementById('btnLogin')?.addEventListener('click', async () => {
    const user = document.getElementById('login-username').value;
    const pass = document.getElementById('login-password').value;
    
    if (!user || !pass) {
        authMessage.innerText = "Vui lòng nhập đủ thông tin!";
        authMessage.className = "text-danger mt-3 fw-bold text-center";
        return;
    }

    authMessage.innerText = "Đang kết nối...";
    authMessage.className = "text-info mt-3 fw-bold text-center";

    try {
        // API Login vẫn dùng fetch thường vì nó không cần Check Token
        const response = await fetch('/api/auth/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username: user, password: pass }),
            credentials: 'include'
        });
        const data = await response.json();
        
        if (response.ok) {
            localStorage.setItem('username', user);
            currentUsername = user;
            showMainApp();
        } else {
            authMessage.innerText = data.error || data.message || "Sai tài khoản/mật khẩu!";
            authMessage.className = "text-danger mt-3 fw-bold text-center";
        }
    } catch (error) { 
        authMessage.innerText = "Lỗi kết nối máy chủ!"; 
        authMessage.className = "text-danger mt-3 fw-bold text-center";
    }
});

document.getElementById('btnSubmitRegister')?.addEventListener('click', async () => {
    const user = document.getElementById('reg-username').value;
    const pass = document.getElementById('reg-password').value;
    const confirmPass = document.getElementById('reg-confirm-password').value;
    
    if (!user || !pass || !confirmPass) {
        authMessage.innerText = "Vui lòng điền đầy đủ các ô!";
        authMessage.className = "text-danger mt-3 fw-bold text-center";
        return;
    }

    if (pass !== confirmPass) {
        authMessage.innerText = "Mật khẩu xác nhận không khớp!";
        authMessage.className = "text-danger mt-3 fw-bold text-center";
        return;
    }

    authMessage.innerText = "Đang tạo tài khoản...";
    authMessage.className = "text-info mt-3 fw-bold text-center";

    try {
        const response = await fetch('/api/auth/register', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username: user.trim(), password: pass.trim() })
        });
        const data = await response.json();

        if (response.ok) {
            authMessage.innerText = "Đăng ký thành công! Đang chuyển về Đăng nhập...";
            authMessage.className = "text-success mt-3 fw-bold text-center";
            
            document.getElementById('reg-username').value = '';
            document.getElementById('reg-password').value = '';
            document.getElementById('reg-confirm-password').value = '';

            setTimeout(() => { document.getElementById('linkToLogin').click(); }, 1500);
        } else {
            authMessage.innerText = data.error || data.message || "Tên đăng nhập đã tồn tại!";
            authMessage.className = "text-danger mt-3 fw-bold text-center";
        }
    } catch (error) {
        authMessage.innerText = "Lỗi kết nối đến máy chủ!";
        authMessage.className = "text-danger mt-3 fw-bold text-center";
    }
});

document.getElementById('btnLogout')?.addEventListener('click', async () => {
    disconnectWebSocket();
    
    localStorage.removeItem('username');
    currentUsername = null;
    
    appContainer.classList.add('hidden');
    authContainer.classList.remove('hidden');
    
    const loginUserEl = document.getElementById('login-username');
    const loginPassEl = document.getElementById('login-password');
    if (loginUserEl) loginUserEl.value = '';
    if (loginPassEl) loginPassEl.value = '';
    
    authMessage.innerText = ''; 
    document.getElementById('progress-list').replaceChildren();
    progressRows.clear();
    document.getElementById('progress-container').classList.add('hidden');
    try { await fetch('/api/auth/logout', { method: 'POST', credentials: 'include' }); } catch(e) {}
});

// ==========================================
// 5. UPLOAD LOGIC
// ==========================================
const btnCreateFolder = document.getElementById('btnCreateFolder');
if (btnCreateFolder) {
    btnCreateFolder.addEventListener('click', async () => {
        const folderName = prompt("Vui lòng nhập tên thư mục mới:");
        if (!folderName || folderName.trim() === "") return;
        try {
            const response = await fetchWithAuth('/api/folders', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ folderName: folderName.trim(), parentId: currentFolderId })
            });
            if (response.ok) loadFolder(currentFolderId);
            else alert("Lỗi tạo thư mục!");
        } catch (error) { alert("Lỗi kết nối!"); }
    });
}

const btnUploadLocal = document.getElementById('btnUploadLocal');
if (btnUploadLocal) {
    btnUploadLocal.addEventListener('click', (event) => {
        event.preventDefault();
        fileInput.click(); 
    });
}

const btnUploadYoutube = document.getElementById('btnUploadYoutube');
if (btnUploadYoutube) {
    btnUploadYoutube.addEventListener('click', async (event) => {
        event.preventDefault();
        const youtubeUrl = prompt("Vui lòng dán đường link YouTube vào đây:");
        if (!youtubeUrl || youtubeUrl.trim() === "") return;

        const originalText = btnUploadYoutube.innerHTML;
        btnUploadYoutube.innerHTML = '⏳ Đang xử lý...';
        btnUploadYoutube.style.pointerEvents = 'none';

        try {
            const response = await fetchWithAuth('/api/files/youtube', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ url: youtubeUrl.trim(), folderId: currentFolderId })
            });

            if (response.ok) {
                alert("Đã bắt đầu tiến trình tải video từ YouTube!");
                loadFolder(currentFolderId); 
            } else {
                const data = await response.json();
                alert("Lỗi: " + (data.error || "Không thể tải video này"));
            }
        } catch (error) { alert("Lỗi kết nối đến máy chủ!"); }
        finally {
            btnUploadYoutube.innerHTML = originalText;
            btnUploadYoutube.style.pointerEvents = 'auto';
        }
    });
}

// Upload sequential 5 MB chunks, then commit the assembled file.
fileInput.addEventListener('change', async (event) => {
    const file = event.target.files[0];
    if (!file) return;

    const originalText = btnUploadDropdown.innerHTML;
    const uploadFolderId = currentFolderId;
    const uploadUser = currentUsername;
    const chunkSize = 5 * 1024 * 1024;
    const totalChunks = Math.max(1, Math.ceil(file.size / chunkSize));
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 15) | 64;
    bytes[8] = (bytes[8] & 63) | 128;
    const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
    const fileGuid = [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join('-');
    const taskId = 'upload-' + fileGuid;
    const taskName = "Tải tệp: " + file.name;

    btnUploadDropdown.innerText = '⏳ Đang tải...';
    btnUploadDropdown.disabled = true;
    try {
        if (totalChunks > 10000) throw new Error('File vượt quá giới hạn 10000 mảnh.');
        for (let chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
            if (currentUsername !== uploadUser) throw new Error('Phiên tải lên đã kết thúc.');
            const formData = new FormData();
            formData.append('file', file.slice(chunkIndex * chunkSize, (chunkIndex + 1) * chunkSize), 'chunk');
            formData.append('fileGuid', fileGuid);
            formData.append('chunkIndex', chunkIndex);
            formData.append('totalChunks', totalChunks);
            const response = await fetchWithAuth('/api/files/upload-chunk', { method: 'POST', body: formData });
            if (!response.ok) {
                const data = await response.json();
                throw new Error(data.error || 'Không thể lưu mảnh file.');
            }
            handleRealtimeProgress({ taskId, taskName, progress: Math.round((chunkIndex + 1) / totalChunks * 95), status: 'PROCESSING' });
        }
        if (currentUsername !== uploadUser) throw new Error('Phiên tải lên đã kết thúc.');
        const response = await fetchWithAuth('/api/files/merge-chunks', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ fileGuid, originalFilename: file.name, totalChunks, folderId: uploadFolderId })
        });
        if (!response.ok) {
            const data = await response.json();
            throw new Error(data.error || 'Không thể gộp file.');
        }
        if (currentUsername === uploadUser) {
            handleRealtimeProgress({ taskId, taskName, progress: 100, status: 'COMPLETED' });
        }
    } catch (error) {
        if (currentUsername === uploadUser) {
            handleRealtimeProgress({ taskId, taskName, progress: 0, status: 'FAILED', message: error.message });
        }
    } finally {
        btnUploadDropdown.innerHTML = originalText;
        btnUploadDropdown.disabled = false;
        fileInput.value = '';
    }
});
