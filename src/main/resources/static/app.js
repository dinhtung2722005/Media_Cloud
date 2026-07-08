const authContainer = document.getElementById('auth-container');
const appContainer = document.getElementById('app-container');
const authMessage = document.getElementById('auth-message');
const welcomeText = document.getElementById('welcome-text');
const mediaList = document.getElementById('media-list');

let currentUsername = localStorage.getItem('username');
let currentFolderId = null;
if (currentUsername) {
    showMainApp();
}

// --- XỬ LÝ ĐĂNG NHẬP ---
document.getElementById('btnLogin').addEventListener('click', async () => {
    const user = document.getElementById('username').value;
    const pass = document.getElementById('password').value;
    authMessage.innerText = "Đang kết nối...";

    try {
        const response = await fetch('/api/auth/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username: user, password: pass }),
            credentials: 'include' // <--- Ép trình duyệt nhận Cookie từ Backend
        });

        const data = await response.json();

        if (response.ok) {
            localStorage.setItem('username', user);
            currentUsername = user;
            showMainApp();
        } else {
            authMessage.innerText = data.error || data.message || "Sai tài khoản hoặc mật khẩu!";
        }
    } catch (error) {
        authMessage.innerText = "Lỗi kết nối đến máy chủ!";
    }
});

// --- XỬ LÝ ĐĂNG XUẤT ---
document.getElementById('btnLogout').addEventListener('click', async () => {
    await fetch('/api/auth/logout', { 
        method: 'POST',
        credentials: 'include' 
    });

    localStorage.removeItem('username');
    currentUsername = null;
    
    appContainer.classList.add('hidden');
    authContainer.classList.remove('hidden');
    document.getElementById('username').value = '';
    document.getElementById('password').value = '';
    
    // [THÊM DÒNG NÀY] Xóa dòng chữ Đang kết nối... khi bị đá văng ra ngoài
    authMessage.innerText = ''; 
});

function showMainApp() {
    authContainer.classList.add('hidden');
    appContainer.classList.remove('hidden');
    welcomeText.innerText = "Xin chào, " + currentUsername;
    loadFolder(null); 
}
async function deleteFolder(folderId, event) {
    // Ngăn chặn sự kiện click lan xuống thẻ mở thư mục bên dưới
    event.stopPropagation(); 

    if (!confirm("Bạn có chắc chắn muốn xóa thư mục này và TOÀN BỘ file bên trong không? Hành động này không thể hoàn tác!")) {
        return;
    }

    try {
        const response = await fetch(`/api/folders/${folderId}`, {
            method: 'DELETE',
            credentials: 'include' // Gửi kèm Cookie xác thực
        });

        if (response.ok) {
            // Tải lại danh sách hiện tại để thấy thư mục đã biến mất
            loadFolder(currentFolderId);
        } else {
            const data = await response.json();
            alert("Lỗi xóa thư mục: " + (data.error || "Không thể xóa"));
        }
    } catch (error) {
        alert("Lỗi kết nối máy chủ!");
    }
}

async function deleteFile(fileId, event) {
    event.stopPropagation();

    if (!confirm("Bạn có chắc chắn muốn xóa tệp này không?")) {
        return;
    }

    try {
        const response = await fetch(`/api/files/${fileId}`, {
            method: 'DELETE',
            credentials: 'include'
        });

        if (response.ok) {
            loadFolder(currentFolderId);
        } else {
            const data = await response.json();
            alert("Lỗi xóa file: " + (data.error || "Không thể xóa"));
        }
    } catch (error) {
        alert("Lỗi kết nối máy chủ!");
    }
}
// --- TẢI DỮ LIỆU ---
async function loadFolder(folderId) {
    currentFolderId = folderId;
    mediaList.innerHTML = '<div class="col-12 text-center text-muted">Đang tải dữ liệu...</div>';
    let url = folderId ? `/api/folders/${folderId}` : '/api/folders';

    try {
        const response = await fetch(url, { 
            method: 'GET',
            credentials: 'include' // <--- Ép gửi kèm Cookie để lấy danh sách Thư mục
        });

        if (response.ok) {
            const data = await response.json();
            renderMedia(data, folderId);
        } else {
            document.getElementById('btnLogout').click();
            alert("Phiên đăng nhập hết hạn!");
        }
    } catch (error) {
        mediaList.innerHTML = '<div class="col-12 text-danger">Lỗi mạng!</div>';
    }
}

// --- VẼ GIAO DIỆN ---
function renderMedia(data, isInsideFolder) {
    mediaList.innerHTML = ''; 
    let folders = isInsideFolder ? [] : data;
    let files = isInsideFolder ? (data.files || []) : [];

    folders.forEach(folder => {
        const div = document.createElement('div');
        div.className = 'col-md-3 col-sm-6';
        div.innerHTML = `
            <div class="card shadow-sm text-center p-3 position-relative">
                <button onclick="deleteFolder('${folder.id}', event)" class="btn btn-sm btn-danger position-absolute top-0 end-0 m-1" title="Xóa thư mục">&times;</button>
                
                <div style="cursor: pointer;" onclick="loadFolder('${folder.id}')">
                    <h1 class="text-warning mb-0">📁</h1>
                    <p class="mb-0 text-truncate fw-bold mt-2">${folder.folderName}</p>
                </div>
            </div>`;
        mediaList.appendChild(div);
    });

    files.forEach(file => {
        const div = document.createElement('div');
        div.className = 'col-md-3 col-sm-6';
        div.innerHTML = `
            <div class="card shadow-sm text-center p-3 position-relative">
                <button onclick="deleteFile('${file.id}', event)" class="btn btn-sm btn-danger position-absolute top-0 end-0 m-1" title="Xóa file">&times;</button>
                
                <h1 class="text-primary mb-0">${file.extension === 'mp4' ? '🎞️' : '📄'}</h1>
                <p class="mb-0 text-truncate fw-bold mt-2" title="${file.title}">${file.title}</p>
                <div class="mt-2">
                    <a href="/api/files/download/${file.id}" class="btn btn-sm btn-outline-secondary">Tải về</a>
                </div>
            </div>`;
        mediaList.appendChild(div);
    });
    const fileInput = document.createElement('input');
fileInput.type = 'file';
fileInput.style.display = 'none';
document.body.appendChild(fileInput);

// 2. Khi người dùng bấm nút "Tải file lên" -> Mở hộp thoại chọn file
const btnUpload = document.getElementById('btnUpload');
if (btnUpload) {
    btnUpload.addEventListener('click', () => {
        fileInput.click(); // Giả lập hành động click vào thẻ input ẩn
    });
}

// 3. Khi người dùng chọn file xong -> Bắt đầu Upload
fileInput.addEventListener('change', async (event) => {
    const file = event.target.files[0];
    if (!file) return;

    // Đổi giao diện nút để báo hiệu đang tải
    const originalText = btnUpload.innerHTML;
    btnUpload.innerHTML = '⏳ Đang tải...';
    btnUpload.disabled = true;

    // Đóng gói file vào FormData (Chuẩn HTML5 để gửi file)
    const formData = new FormData();
    formData.append('file', file);
    
    // Nếu đang đứng trong một thư mục cụ thể, gửi kèm ID thư mục đó
    if (currentFolderId) {
        formData.append('folderId', currentFolderId);
    }

    try {
        // Gửi API lên Spring Boot (Nhớ phải có credentials: 'include')
        const response = await fetch('/api/files/upload', {
            method: 'POST',
            body: formData,
            credentials: 'include' 
        });

        if (response.ok) {
            // Tải thành công -> Refresh lại danh sách thư mục hiện tại
            loadFolder(currentFolderId); 
        } else {
            const data = await response.json();
            alert("Lỗi tải lên: " + (data.error || data.message || "Tệp không hợp lệ"));
        }
    } catch (error) {
        alert("Lỗi mạng khi kết nối đến máy chủ!");
    } finally {
        btnUpload.innerHTML = originalText;
        btnUpload.disabled = false;
        fileInput.value = ''; 
    }
});

const btnCreateFolder = document.getElementById('btnCreateFolder');

if (btnCreateFolder) {
    btnCreateFolder.addEventListener('click', async () => {
        // Hiện hộp thoại yêu cầu người dùng nhập tên thư mục
        const folderName = prompt("Vui lòng nhập tên thư mục mới:");
        
        // Nếu người dùng bấm Hủy hoặc không nhập gì thì bỏ qua
        if (!folderName || folderName.trim() === "") return;

        try {
            // Gửi lệnh lên Backend
            const response = await fetch('/api/folders', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ 
                    folderName: folderName.trim(),
                    parentId: currentFolderId // Báo cho Server biết thư mục cha là ai (nếu có)
                }),
                credentials: 'include' // Bắt buộc phải có để gửi Cookie
            });

            if (response.ok) {
                loadFolder(currentFolderId);
            } else {
                const data = await response.json();
                alert("Lỗi tạo thư mục: " + (data.error || data.message || "Tên không hợp lệ!"));
            }
        } catch (error) {
            alert("Lỗi kết nối đến máy chủ!");
        }
    });
}
}