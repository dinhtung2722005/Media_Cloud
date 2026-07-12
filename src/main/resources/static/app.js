// ==========================================
// 1. KHAI BÁO BIẾN TOÀN CỤC & DOM SELECTORS
// ==========================================
const authContainer = document.getElementById('auth-container');
const appContainer = document.getElementById('app-container');
const authMessage = document.getElementById('auth-message');
const welcomeText = document.getElementById('welcome-text');
const mediaList = document.getElementById('media-list');

let currentUsername = localStorage.getItem('username');
let currentFolderId = null;

// Khởi tạo thẻ file ẩn duy nhất 1 lần
const fileInput = document.createElement('input');
fileInput.type = 'file';
fileInput.style.display = 'none';
document.body.appendChild(fileInput);

if (currentUsername) {
    showMainApp();
}

// ==========================================
// 2. CÁC HÀM XỬ LÝ LOGIC GIAO DIỆN
// ==========================================
function showMainApp() {
    authContainer.classList.add('hidden');
    appContainer.classList.remove('hidden');
    welcomeText.innerText = "Xin chào, " + currentUsername;
    loadFolder(null); 
}

async function loadFolder(folderId) {
    currentFolderId = folderId;
    mediaList.innerHTML = '<div class="col-12 text-center text-muted py-5">Đang tải dữ liệu...</div>';
    let url = folderId ? `/api/folders/${folderId}` : '/api/folders';

    try {
        const response = await fetch(url, { 
            method: 'GET',
            credentials: 'include'
        });

        if (response.ok) {
            const data = await response.json();
            renderMedia(data, folderId);
        } else {
            document.getElementById('btnLogout').click();
            alert("Phiên đăng nhập hết hạn!");
        }
    } catch (error) {
        mediaList.innerHTML = '<div class="col-12 text-danger text-center">Lỗi mạng kết nối!</div>';
    }
}

// --- VẼ GIAO DIỆN ---
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

    // 1. Vẽ danh sách Thư mục
    folders.forEach(folder => {
        const safeName = folder.folderName.replace(/'/g, "\\'"); 
        const div = document.createElement('div');
        div.className = 'col-md-3 col-sm-6 mb-3';
        div.innerHTML = `
            <div class="card shadow-sm text-center p-3 position-relative animate__animated animate__fadeIn">
                
                <div class="dropdown position-absolute top-0 end-0 m-1">
                    <button class="btn btn-link text-secondary p-0 border-0 fs-4 lh-1" type="button" data-bs-toggle="dropdown" aria-expanded="false" style="text-decoration: none;" onclick="event.stopPropagation();">
                        ⋮
                    </button>
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

    // 2. Vẽ danh sách File
    // 2. Vẽ danh sách File (ĐÃ CẬP NHẬT THUMBNAIL)
    files.forEach(file => {
        const safeName = file.title.replace(/'/g, "\\'");
        
        // Nhận diện định dạng
        const ext = (file.extension || '').toLowerCase();
        const isVideo = ['mp4', 'webm', 'ogg', 'mov'].includes(ext);
        const isImage = ['jpg', 'jpeg', 'png', 'gif', 'webp'].includes(ext);

        // Xử lý khối hiển thị (Thumbnail hoặc Icon)
        let visualContent = `<h1 class="text-primary mb-0" style="font-size: 3rem;">📄</h1>`; // Mặc định là icon Tài liệu
        
        if (isImage) {
            // Nếu là ảnh -> Hiện thẳng ảnh ra
            visualContent = `<img src="/api/files/stream/${file.id}" class="rounded shadow-sm" style="width: 100%; height: 100px; object-fit: cover;" alt="img">`;
        } else if (isVideo) {
            // Nếu là video -> Dùng thẻ video chặn ở giây thứ 1.0 (#t=1.0) để làm Thumbnail
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
                    <button class="btn btn-light shadow-sm p-0 border-0 rounded-circle d-flex justify-content-center align-items-center" style="width: 28px; height: 28px; opacity: 0.9;" type="button" data-bs-toggle="dropdown" aria-expanded="false" onclick="event.stopPropagation();">
                        ⋮
                    </button>
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
// ==========================================
// 3. LOGIC THAO TÁC CRUD (XÓA, ĐỔI TÊN)
// ==========================================
async function deleteFolder(folderId, event) {
    event.stopPropagation(); 
    if (!confirm("Bạn có chắc chắn muốn xóa thư mục này và TOÀN BỘ file bên trong không?")) return;
    try {
        const response = await fetch(`/api/folders/${folderId}`, { method: 'DELETE', credentials: 'include' });
        if (response.ok) loadFolder(currentFolderId);
        else alert("Lỗi xóa thư mục!");
    } catch (error) { alert("Lỗi kết nối máy chủ!"); }
}
function playVideo(fileId, fileName) {
    // 1. Tạo một lớp phủ đen mờ che toàn bộ trang web
    const overlay = document.createElement('div');
    overlay.id = 'video-player-overlay';
    overlay.style.cssText = 'position: fixed; top: 0; left: 0; width: 100vw; height: 100vh; background: rgba(0,0,0,0.9); z-index: 9999; display: flex; flex-direction: column; align-items: center; justify-content: center;';

    // 2. Tạo nút Đóng
    const closeBtn = document.createElement('button');
    closeBtn.innerHTML = '&times; Đóng Video';
    closeBtn.className = 'btn btn-danger mb-3';
    closeBtn.onclick = () => document.body.removeChild(overlay); // Bấm vào thì xóa lớp phủ đi

    // 3. Tiêu đề phim
    const title = document.createElement('h4');
    title.innerText = fileName;
    title.className = 'text-white mb-3';

    // 4. Khung phát Video (Phép thuật Cookie nằm ở đây)
    const video = document.createElement('video');
    // Trình duyệt sẽ tự động mang Cookie đập vào cổng /api/files/stream
    video.src = `/api/files/stream/${fileId}`; 
    video.controls = true; // Hiện thanh thời gian, âm lượng
    video.autoplay = true; // Tự động chạy
    video.style.cssText = 'max-width: 90%; max-height: 75%; border-radius: 8px; box-shadow: 0 4px 15px rgba(0,0,0,0.5); background: black;';

    // Lắp ráp mọi thứ và ném ra màn hình
    overlay.appendChild(closeBtn);
    overlay.appendChild(title);
    overlay.appendChild(video);
    document.body.appendChild(overlay);
}
async function deleteFile(fileId, event) {
    event.stopPropagation();
    if (!confirm("Bạn có chắc chắn muốn xóa tệp này không?")) return;
    try {
        const response = await fetch(`/api/files/${fileId}`, { method: 'DELETE', credentials: 'include' });
        if (response.ok) loadFolder(currentFolderId);
        else alert("Lỗi xóa file!");
    } catch (error) { alert("Lỗi kết nối máy chủ!"); }
}
function shareItem(id, type, event) {
    event.stopPropagation(); // Chặn sự kiện bấm nhầm mở folder/phát video
    
    // Tạm thời làm thông báo mẫu, sau này bạn làm API chia sẻ thì ghép vào đây nhé
    alert(`Tính năng tạo liên kết chia sẻ cho ${type === 'folder' ? 'thư mục' : 'tệp tin'} đang được phát triển!\nID: ${id}`);
}
async function renameFolder(folderId, currentName, event) {
    event.stopPropagation();
    const newName = prompt("Đổi tên thư mục:", currentName);
    if (!newName || newName.trim() === "" || newName.trim() === currentName) return;
    try {
        const response = await fetch(`/api/folders/${folderId}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ folderName: newName.trim() }),
            credentials: 'include'
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
        const response = await fetch(`/api/files/${fileId}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ title: newName.trim() }), 
            credentials: 'include'
        });
        if (response.ok) loadFolder(currentFolderId);
        else alert("Lỗi đổi tên tệp!");
    } catch (error) { alert("Lỗi kết nối máy chủ!"); }
}

// ==========================================
// 4. LẮNG NGHE SỰ KIỆN GIAO DIỆN (ĐĂNG KÝ 1 LẦN DUY NHẤT)
// ==========================================
document.getElementById('btnLogin')?.addEventListener('click', async () => {
    const user = document.getElementById('username').value;
    const pass = document.getElementById('password').value;
    authMessage.innerText = "Đang kết nối...";
    try {
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
        }
    } catch (error) { authMessage.innerText = "Lỗi kết nối!"; }
});

document.getElementById('btnLogout')?.addEventListener('click', async () => {
    await fetch('/api/auth/logout', { method: 'POST', credentials: 'include' });
    localStorage.removeItem('username');
    currentUsername = null;
    appContainer.classList.add('hidden');
    authContainer.classList.remove('hidden');
    document.getElementById('username').value = '';
    document.getElementById('password').value = '';
    authMessage.innerText = ''; 
});

const btnCreateFolder = document.getElementById('btnCreateFolder');
if (btnCreateFolder) {
    btnCreateFolder.addEventListener('click', async () => {
        const folderName = prompt("Vui lòng nhập tên thư mục mới:");
        if (!folderName || folderName.trim() === "") return;
        try {
            const response = await fetch('/api/folders', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ folderName: folderName.trim(), parentId: currentFolderId }),
                credentials: 'include'
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
        fileInput.click(); // Mở hộp thoại chọn file của máy tính
    });
}

// 2. Xử lý nút "Tải từ YouTube"
const btnUploadYoutube = document.getElementById('btnUploadYoutube');
if (btnUploadYoutube) {
    btnUploadYoutube.addEventListener('click', async (event) => {
        event.preventDefault();
        
        // Bật hộp thoại nhập link YouTube
        const youtubeUrl = prompt("Vui lòng dán đường link YouTube vào đây:");
        
        if (!youtubeUrl || youtubeUrl.trim() === "") return;

        // Báo hiệu đang xử lý
        const originalText = btnUploadYoutube.innerHTML;
        btnUploadYoutube.innerHTML = '⏳ Đang xử lý...';
        btnUploadYoutube.style.pointerEvents = 'none';

        try {
            // Gửi Link YouTube lên Backend xử lý
            const response = await fetch('/api/files/youtube', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ 
                    url: youtubeUrl.trim(),
                    folderId: currentFolderId 
                }),
                credentials: 'include'
            });

            if (response.ok) {
                alert("Đã bắt đầu tiến trình tải video từ YouTube!");
                loadFolder(currentFolderId); 
            } else {
                const data = await response.json();
                alert("Lỗi: " + (data.error || "Không thể tải video này"));
            }
        } catch (error) {
            alert("Lỗi kết nối đến máy chủ!");
        } finally {
            // Khôi phục nút bấm
            btnUploadYoutube.innerHTML = originalText;
            btnUploadYoutube.style.pointerEvents = 'auto';
        }
    });
}

fileInput.addEventListener('change', async (event) => {
    const file = event.target.files[0];
    if (!file) return;

    const originalText = btnUpload.innerHTML;
    btnUpload.innerHTML = '⏳ Đang tải...';
    btnUpload.disabled = true;

    const formData = new FormData();
    formData.append('file', file);
    if (currentFolderId) formData.append('folderId', currentFolderId);

    try {
        const response = await fetch('/api/files/upload', { method: 'POST', body: formData, credentials: 'include' });
        if (response.ok) loadFolder(currentFolderId); 
        else alert("Lỗi tải lên!");
    } catch (error) {
        alert("Lỗi mạng khi kết nối!");
    } finally {
        btnUpload.innerHTML = originalText;
        btnUpload.disabled = false;
        fileInput.value = ''; 
    }
});