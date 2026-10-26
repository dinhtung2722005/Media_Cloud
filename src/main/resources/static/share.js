'use strict';
(async function () {
    const title = document.getElementById('share-title');
    const status = document.getElementById('share-status');
    const preview = document.getElementById('share-preview');
    const download = document.getElementById('share-download');
    const token = location.pathname.startsWith('/s/') ? location.pathname.slice(3) : '';
    const failed = () => {
        title.textContent = 'Liên kết không còn khả dụng';
        status.textContent = 'Liên kết có thể đã hết hạn, bị thu hồi hoặc tệp đã được xóa. Hãy liên hệ người gửi để nhận liên kết mới.';
        preview.replaceChildren();
        download.classList.add('hidden');
    };
    if (!/^[A-Za-z0-9_-]{43}$/.test(token)) { failed(); return; }
    const endpoint = '/api/public/shares/' + encodeURIComponent(token);
    try {
        const response = await fetch(endpoint, { credentials: 'omit', cache: 'no-store', redirect: 'error' });
        if (!response.ok) {
            if (response.status >= 500) throw new Error('unavailable');
            failed(); return;
        }
        const file = await response.json();
        title.textContent = file.title;
        document.title = file.title + ' — CloudMedia';
        const bytes = Math.max(0, Number(file.fileSize) || 0);
        const units = ['B', 'KB', 'MB', 'GB', 'TB'];
        const unit = bytes ? Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024))) : 0;
        document.getElementById('share-details').textContent = String(file.extension || 'Tệp').toUpperCase() + ' · ' + (bytes / Math.pow(1024, unit)).toLocaleString('vi-VN', { maximumFractionDigits: 1 }) + ' ' + units[unit];
        document.getElementById('share-expiry').textContent = 'Liên kết có hiệu lực đến ' + new Date(file.expiresAt).toLocaleString('vi-VN') + '.';
        download.href = endpoint + '/download';
        download.classList.remove('hidden');
        preview.replaceChildren();
        const extension = String(file.extension || '').toLowerCase();
        let media;
        if (['mp4', 'webm', 'mov', 'm4v', 'mkv', 'ogv'].includes(extension)) media = document.createElement('video');
        else if (['mp3', 'wav', 'm4a', 'aac', 'ogg', 'flac', 'opus'].includes(extension)) media = document.createElement('audio');
        else if (['jpg', 'jpeg', 'png', 'gif', 'webp', 'avif', 'bmp'].includes(extension)) media = document.createElement('img');
        if (media) {
            if (media.tagName === 'IMG') media.alt = file.title;
            else { media.controls = true; media.preload = 'metadata'; media.playsInline = true; }
            media.src = endpoint + '/stream';
            media.addEventListener('error', () => { status.textContent = 'Chưa thể xem trước tệp này. Bạn có thể tải về hoặc nhờ người gửi kiểm tra lại liên kết.'; });
            preview.appendChild(media);
            status.textContent = 'Bạn có thể xem nội dung tại đây hoặc tải tệp về.';
        } else {
            const placeholder = document.createElement('span');
            placeholder.className = 'placeholder'; placeholder.textContent = 'Tệp đã sẵn sàng để tải về';
            preview.appendChild(placeholder); status.textContent = 'Tải tệp để mở bằng ứng dụng phù hợp.';
        }
    } catch (error) {
        title.textContent = 'Chưa thể mở tệp'; status.textContent = 'Kết nối đang gián đoạn. Vui lòng tải lại trang để thử lại.'; preview.replaceChildren();
    }
})();
