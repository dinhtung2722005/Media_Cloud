// ==========================================
// 1. KHAI BÁO BIẾN TOÀN CỤC
// ==========================================
const authContainer = document.getElementById('auth-container');
const appContainer = document.getElementById('app-container');
const authMessage = document.getElementById('auth-message');
const welcomeText = document.getElementById('welcome-text');
const mediaList = document.getElementById('media-list');

let currentFolderId = null;
let jwtToken = localStorage.getItem('jwt_token');
let currentUsername = localStorage.getItem('username');

// ==========================================
// 2. KIỂM TRA TRẠNG THÁI ĐĂNG NHẬP
// ==========================================
if (jwtToken && jwtToken !== "undefined" && jwtToken !== "null") {
    showMainApp();
}

// ==========================================
// 3. XỬ LÝ ĐĂNG NHẬP
// ==========================================
document.getElementById('btnLogin').addEventListener('click', async () => {
    const user = document.getElementById('username').value;
    const pass = document.getElementById('password').value;
    authMessage.innerText = "Đang kết nối...";

    try {
        const response = await fetch('/api/auth/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username: user, password: pass })
        });

        const data = await response.json();

        if (response.ok) {
            const actualToken = data.accessToken || data.token; 
            if (!actualToken) {
                authMessage.innerText = "Lỗi: Không tìm thấy Token từ Backend!";
                return;
            }
            localStorage.setItem('jwt_token', actualToken);
            localStorage.setItem('username', user);
            jwtToken = actualToken;
            currentUsername = user;
            
            showMainApp();
        } else {
            authMessage.innerText = data.error || data.message || "Sai tài khoản hoặc mật khẩu!";
        }
    } catch (error) {
        authMessage.innerText = "Lỗi kết nối đến máy chủ!";
    }
});

// ==========================================
// 4. XỬ LÝ ĐĂNG XUẤT
// ==========================================
document.getElementById('btnLogout').addEventListener('click', () => {
    localStorage.removeItem('jwt_token');
    localStorage.removeItem('username');
    jwtToken = null;
    currentUsername = null;
    
    appContainer.classList.add('hidden');
    authContainer.classList.remove('hidden');
    document.getElementById('username').value = '';
    document.getElementById('password').value = '';
    authMessage.innerText = '';
});

// ==========================================
// 5. CÁC HÀM TIỆN ÍCH & VẼ GIAO DIỆN
// ==========================================
function showMainApp() {
    authContainer.classList.add('hidden');
    appContainer.classList.remove('hidden');
    welcomeText.innerText = "Xin chào, " + currentUsername;
    
    // Gọi tải dữ liệu thư mục gốc (Nơi bạn bị lỗi gõ nhầm ban nãy)
    loadFolder(null); 
}

async function loadFolder(folderId) {
    currentFolderId = folderId;
    mediaList.innerHTML = '<div class="col-12 text-center text-muted">Đang tải dữ liệu...</div>';

    try {
        let url = '/api/folders';
        if (folderId) {
            url = `/api/folders/${folderId}`;
        }

        const response = await fetch(url, {
            method: 'GET',
            headers: {
                'Authorization': `Bearer ${jwtToken}`
            }
        });

        if (response.ok) {
            const data = await response.json();
            renderMedia(data, folderId);
        } else {
            if(response.status === 401 || response.status === 403) {
                alert("Phiên đăng nhập hết hạn, vui lòng đăng nhập lại!");
                document.getElementById('btnLogout').click();
            } else {
                mediaList.innerHTML = '<div class="col-12 text-danger">Lỗi tải dữ liệu.</div>';
            }
        }
    } catch (error) {
        mediaList.innerHTML = '<div class="col-12 text-danger">Mất kết nối máy chủ!</div>';
    }
}

function renderMedia(data, isInsideFolder) {
    mediaList.innerHTML = ''; 
    
    let folders = [];
    let files = [];

    if (!isInsideFolder) {
        folders = data;
    } else {
        folders = []; 
        files = data.files || [];
    }

    if (folders.length === 0 && files.length === 0) {
        mediaList.innerHTML = '<div class="col-12 text-center text-muted">Thư mục trống</div>';
        return;
    }

    folders.forEach(folder => {
        const div = document.createElement('div');
        div.className = 'col-md-3 col-sm-6';
        div.innerHTML = `
            <div class="card shadow-sm text-center p-3" style="cursor: pointer;" onclick="loadFolder('${folder.id}')">
                <h1 class="text-warning mb-0">📁</h1>
                <p class="mb-0 text-truncate fw-bold mt-2">${folder.folderName}</p>
            </div>
        `;
        mediaList.appendChild(div);
    });

    files.forEach(file => {
        const div = document.createElement('div');
        div.className = 'col-md-3 col-sm-6';
        div.innerHTML = `
            <div class="card shadow-sm text-center p-3">
                <h1 class="text-primary mb-0">${file.extension === 'mp4' ? '🎞️' : '📄'}</h1>
                <p class="mb-0 text-truncate fw-bold mt-2" title="${file.title}">${file.title}</p>
                <div class="mt-2">
                    <button onclick="downloadFile(event, '${file.id}', '${file.title}')" class="btn btn-sm btn-outline-secondary">Tải về</button>
                </div>
            </div>
        `;
        mediaList.appendChild(div);
    });
}

// ==========================================
// 6. XỬ LÝ TẢI FILE CÓ ĐÍNH KÈM TOKEN
// ==========================================
async function downloadFile(event, fileId, fileName) {
    try {
        const btn = event.target;
        const originalText = btn.innerText;
        btn.innerText = 'Đang tải...';
        btn.disabled = true;

        const response = await fetch(`/api/files/download/${fileId}`, {
            method: 'GET',
            headers: {
                'Authorization': `Bearer ${jwtToken}`
            }
        });

        if (response.ok) {
            // Chuyển dữ liệu tải về thành Blob (nhị phân)
            const blob = await response.blob();
            // Tạo link ảo
            const url = window.URL.createObjectURL(blob);
            
            // Tạo thẻ a ảo để ép tải về
            const a = document.createElement('a');
            a.href = url;
            a.download = fileName;
            document.body.appendChild(a);
            a.click();
            
            // Xóa rác bộ nhớ
            window.URL.revokeObjectURL(url);
            document.body.removeChild(a);
        } else {
            alert("Lỗi: Không thể tải file hoặc file không tồn tại!");
        }

        btn.innerText = originalText;
        btn.disabled = false;

    } catch (error) {
        console.error("Lỗi download:", error);
        alert("Lỗi kết nối đến máy chủ khi tải file!");
    }
}