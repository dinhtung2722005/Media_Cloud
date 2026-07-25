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
async function fetchWithAuth(url, options = {}) {
    // Luôn đính kèm Cookie
    options.credentials = 'include';

    // Gọi API lần 1
    let response = await fetch(url, options);

    // Nếu Access Token hết hạn
    if (response.status === 401) {
        console.log("Access Token hết hạn! Đang tiến hành làm mới ngầm...");
        
        // Gọi API Refresh
        const refreshResponse = await fetch('/api/auth/refresh', {
            method: 'POST',
            credentials: 'include'
        });

        if (refreshResponse.ok) {
            console.log("Làm mới Token thành công! Đang gọi lại API...");
            // Cấp lại thành công -> Gọi lại API ban đầu
            response = await fetch(url, options);
        } else {
            console.error("Refresh Token hết hạn. Buộc đăng xuất.");
            // Refresh thất bại -> Xóa cache và ép đăng xuất
            document.getElementById('btnLogout').click();
            alert("Phiên đăng nhập đã hết hạn dài ngày, vui lòng đăng nhập lại.");
            return Promise.reject("Phiên đăng nhập hết hạn");
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

function connectWebSocket(username) {
    const socket = new SockJS('/ws');
    stompClient = Stomp.over(socket);
    stompClient.debug = null;

    stompClient.connect({}, function (frame) {
        console.log('WebSocket kết nối thành công cho: ' + username);
        stompClient.subscribe('/topic/progress/global', function (response) {
            const data = JSON.parse(response.body);
            handleRealtimeProgress(data);
        });
    }, function (error) {
        console.warn('Lỗi kết nối WebSocket, thử lại sau 5 giây...', error);
        setTimeout(() => connectWebSocket(username), 5000);
    });
}

function disconnectWebSocket() {
    if (stompClient !== null) {
        stompClient.disconnect();
        stompClient = null;
    }
    console.log("Đã ngắt kết nối WebSocket");
}

function handleRealtimeProgress(data) {
    const progressContainer = document.getElementById('progress-container');
    const progressList = document.getElementById('progress-list');
    if (!progressContainer || !progressList) return;

    progressContainer.classList.remove('hidden');

    let taskId = data.taskId || (data.jobType === 'YOUTUBE_DOWNLOAD' ? 'yt-dlp-task' : 'default-task');
    let taskName = data.taskName || (data.jobType === 'YOUTUBE_DOWNLOAD' ? 'Tải video YouTube' : 'Đang xử lý...');
    let progress = data.progress || 0;
    let status = data.status;

    if (data.progressLine) {
        const match = data.progressLine.match(/([\d.]+)%/);
        if (match && match[1]) {
            progress = Math.round(parseFloat(match[1]));
        } else {
            return; 
        }
    }

    let taskRow = document.getElementById(`task-${taskId}`);

    if (!taskRow) {
        taskRow = document.createElement('div');
        taskRow.id = `task-${taskId}`;
        taskRow.className = 'col-12 p-2 border rounded bg-white shadow-sm mb-2 animate__animated animate__fadeIn';
        taskRow.innerHTML = `
            <div class="d-flex justify-content-between mb-1 small">
                <span class="fw-bold text-truncate" style="max-width: 80%;" id="task-name-${taskId}">${taskName}</span>
                <span class="text-primary fw-bold" id="task-pct-${taskId}">${progress}%</span>
            </div>
            <div class="progress" style="height: 8px;">
                <div class="progress-bar progress-bar-striped progress-bar-animated bg-primary" id="task-bar-${taskId}" role="progressbar" style="width: ${progress}%"></div>
            </div>`;
        progressList.appendChild(taskRow);
    } else {
        document.getElementById(`task-pct-${taskId}`).innerText = `${progress}%`;
        document.getElementById(`task-bar-${taskId}`).style.width = `${progress}%`;
    }

    if (status === 'COMPLETED') {
        const bar = document.getElementById(`task-bar-${taskId}`);
        if(bar) bar.className = 'progress-bar bg-success'; 
        const pct = document.getElementById(`task-pct-${taskId}`);
        if(pct) pct.innerText = 'Xong!';
        
        showToast(`✅ ${taskName} thành công!`, 'bg-success');
        
        setTimeout(() => {
            if(taskRow) taskRow.remove();
            if (progressList.children.length === 0) progressContainer.classList.add('hidden');
        }, 3000);

        loadFolder(currentFolderId);

    } else if (status === 'FAILED') {
        const bar = document.getElementById(`task-bar-${taskId}`);
        if(bar) bar.className = 'progress-bar bg-danger';
        const pct = document.getElementById(`task-pct-${taskId}`);
        if(pct) pct.innerText = 'Lỗi!';
        
        showToast(`❌ Lỗi: ${data.message || 'Tác vụ thất bại!'}`, 'bg-danger');

        setTimeout(() => {
            if(taskRow) taskRow.remove();
            if (progressList.children.length === 0) progressContainer.classList.add('hidden');
        }, 5000);
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

function renderMedia(data, isInsideFolder) {
    mediaList.innerHTML = ''; 
    
    let folders = [];
    let files = [];

    if (isInsideFolder) {
        folders = data.subFolders || data.folders || []; 
        files = data.files || [];
    } else {
        folders = Array.isArray(data) ? data : (data.folders || []);
        files = data.files || [];
    }

    if (folders.length === 0 && files.length === 0) {
        mediaList.innerHTML = '<div class="col-12 text-center text-muted py-5">Thư mục trống</div>';
        return;
    }

    folders.forEach(folder => {
        const safeName = folder.folderName.replace(/'/g, "\\'"); 
        const div = document.createElement('div');
        div.className = 'col-md-3 col-sm-6 mb-3';
        div.innerHTML = `
            <div class="card shadow-sm text-center p-3 position-relative animate__animated animate__fadeIn">
                <div class="dropdown position-absolute top-0 end-0 m-1">
                    <button class="btn btn-link text-secondary p-0 border-0 fs-4 lh-1" type="button" data-bs-toggle="dropdown" aria-expanded="false" style="text-decoration: none;" onclick="event.stopPropagation();">⋮</button>
                    <ul class="dropdown-menu dropdown-menu-end shadow">
                        <li><a class="dropdown-item" href="#" onclick="renameFolder('${folder.id}', '${safeName}', event)">✏️ Sửa tên</a></li>
                        <li><a class="dropdown-item" href="#" onclick="shareItem('${folder.id}', 'folder', event)">🔗 Chia sẻ</a></li>
                        <li><hr class="dropdown-divider"></li>
                        <li><a class="dropdown-item text-danger" href="#" onclick="deleteFolder('${folder.id}', event)">❌ Xóa thư mục</a></li>
                    </ul>
                </div>
                <div style="cursor: pointer;" onclick="loadFolder('${folder.id}')">
                    <h1 class="text-warning mb-0" style="font-size: 3rem;">📁</h1>
                    <p class="mb-0 text-truncate fw-bold mt-2">${folder.folderName}</p>
                </div>
            </div>`;
        mediaList.appendChild(div);
    });

    files.forEach(file => {
        const safeName = file.title.replace(/'/g, "\\'");
        const ext = (file.extension || '').toLowerCase();
        const isVideo = ['mp4', 'webm', 'ogg', 'mov'].includes(ext);
        const isImage = ['jpg', 'jpeg', 'png', 'gif', 'webp'].includes(ext);

        let visualContent = `<h1 class="text-primary mb-0" style="font-size: 3rem;">📄</h1>`; 
        
        if (isImage) {
            visualContent = `<img src="/api/files/stream/${file.id}" class="rounded shadow-sm" style="width: 100%; height: 100px; object-fit: cover;" alt="img">`;
        } else if (isVideo) {
            visualContent = `
                <video src="/api/files/stream/${file.id}#t=1.0" class="rounded shadow-sm bg-dark" style="width: 100%; height: 100px; object-fit: cover;" preload="metadata"></video>
                <div class="position-absolute top-50 start-50 translate-middle text-white fs-2" style="pointer-events: none; text-shadow: 0px 0px 8px rgba(0,0,0,0.8);">▶</div>
            `;
        }

        const div = document.createElement('div');
        div.className = 'col-md-3 col-sm-6 mb-3';
        div.innerHTML = `
            <div class="card shadow-sm text-center p-3 position-relative animate__animated animate__fadeIn h-100 d-flex flex-column">
                <div class="dropdown position-absolute top-0 end-0 m-2 z-3">
                    <button class="btn btn-light shadow-sm p-0 border-0 rounded-circle d-flex justify-content-center align-items-center" style="width: 28px; height: 28px; opacity: 0.9;" type="button" data-bs-toggle="dropdown" aria-expanded="false" onclick="event.stopPropagation();">⋮</button>
                    <ul class="dropdown-menu dropdown-menu-end shadow">
                        <li><a class="dropdown-item" href="#" onclick="renameFile('${file.id}', '${safeName}', event)">✏️ Sửa tên</a></li>
                        <li><a class="dropdown-item" href="#" onclick="shareItem('${file.id}', 'file', event)">🔗 Chia sẻ</a></li>
                        <li><hr class="dropdown-divider"></li>
                        <li><a class="dropdown-item text-danger" href="#" onclick="deleteFile('${file.id}', event)">❌ Xóa file</a></li>
                    </ul>
                </div>
                <div style="cursor: pointer; position: relative;" onclick="${isVideo ? `playVideo('${file.id}', '${safeName}')` : ''}">
                    ${visualContent}
                </div>
                <p class="mb-0 text-truncate fw-bold mt-3" title="${file.title}">${file.title}</p>
                <div class="mt-auto pt-3">
                    <a href="/api/files/download/${file.id}" class="btn btn-sm btn-outline-secondary w-100">Tải về</a>
                </div>
            </div>`;
        mediaList.appendChild(div);
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
    try { await fetch('/api/auth/logout', { method: 'POST', credentials: 'include' }); } catch(e) {}
    
    localStorage.removeItem('username');
    currentUsername = null;
    
    appContainer.classList.add('hidden');
    authContainer.classList.remove('hidden');
    
    const loginUserEl = document.getElementById('login-username');
    const loginPassEl = document.getElementById('login-password');
    if (loginUserEl) loginUserEl.value = '';
    if (loginPassEl) loginPassEl.value = '';
    
    authMessage.innerText = ''; 
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

// Xử lý XHR Upload có khả năng tự động gia hạn Token
fileInput.addEventListener('change', (event) => {
    const file = event.target.files[0];
    if (!file) return;

    const originalText = btnUploadDropdown.innerHTML;
    btnUploadDropdown.innerHTML = '⏳ Đang tải...';
    btnUploadDropdown.disabled = true;

    const formData = new FormData();
    formData.append('file', file);
    if (currentFolderId) formData.append('folderId', currentFolderId);

    const taskId = 'upload-' + Date.now();
    const taskName = "Đang tải tệp: " + file.name;

    // Tách XHR thành hàm đệ quy để có thể Retry nếu dính lỗi 401
    function performXhrUpload() {
        const xhr = new XMLHttpRequest();
        xhr.withCredentials = true; 

        xhr.upload.addEventListener('progress', (e) => {
            if (e.lengthComputable) {
                const percentComplete = Math.round((e.loaded / e.total) * 100);
                handleRealtimeProgress({ taskId: taskId, taskName: taskName, progress: percentComplete, status: 'PROCESSING' });
            }
        });

        xhr.addEventListener('load', async () => {
            if (xhr.status === 201 || xhr.status === 200) {
                handleRealtimeProgress({ taskId: taskId, taskName: taskName, progress: 100, status: 'COMPLETED' });
                resetUploadBtn();
            } 
            // Nếu lỗi 401 (Hết hạn Token), tiến hành làm mới Token ngầm bằng Fetch rồi gọi lại XHR
            else if (xhr.status === 401) {
                console.log("XHR báo lỗi 401, đang thử làm mới thẻ...");
                const refreshRes = await fetch('/api/auth/refresh', { method: 'POST', credentials: 'include' });
                if (refreshRes.ok) {
                    console.log("Đã cấp lại thẻ, đang tiếp tục Upload...");
                    performXhrUpload(); // Đệ quy: Tự động chạy lại hàm XHR này từ đầu
                } else {
                    document.getElementById('btnLogout').click(); // Ép đăng xuất
                }
            } else {
                handleRealtimeProgress({ taskId: taskId, taskName: taskName, progress: 0, status: 'FAILED' });
                alert("Lỗi máy chủ khi lưu file!");
                resetUploadBtn();
            }
        });

        xhr.addEventListener('error', () => {
            handleRealtimeProgress({ taskId: taskId, taskName: taskName, progress: 0, status: 'FAILED' });
            alert("Lỗi mạng, mất kết nối đến máy chủ!");
            resetUploadBtn();
        });

        xhr.open('POST', '/api/files/upload', true);
        xhr.send(formData);
    }

    function resetUploadBtn() {
        btnUploadDropdown.innerHTML = originalText;
        btnUploadDropdown.disabled = false;
        fileInput.value = '';
    }

    // Khởi chạy tiến trình upload
    performXhrUpload();
});