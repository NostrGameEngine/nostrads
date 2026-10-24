import qrcode from '../vendor/qrcode/qrcode.mjs';

function draw(canvas, code, cellSize) {
    const modules = code.getModuleCount();
    const quietZone = 4;
    canvas.width = canvas.height = (modules + quietZone * 2) * cellSize;
    const context = canvas.getContext('2d');
    context.fillStyle = '#fff';
    context.fillRect(0, 0, canvas.width, canvas.height);
    context.fillStyle = '#000';
    for (let row = 0; row < modules; row++) {
        for (let column = 0; column < modules; column++) {
            if (code.isDark(row, column)) {
                context.fillRect((column + quietZone) * cellSize, (row + quietZone) * cellSize, cellSize, cellSize);
            }
        }
    }
}

export default function connectQr(view) {
    const button = view.querySelector('#expand-connect-qr');
    const thumbnail = view.querySelector('#connect-qr');
    const expanded = view.querySelector('#connect-qr-expanded');
    const dialog = view.querySelector('#connect-qr-dialog');
    let code = null;
    button.addEventListener('click', () => {
        if (!code) return;
        draw(expanded, code, 8);
        dialog.showModal();
    });
    view.querySelector('#close-connect-qr').addEventListener('click', () => dialog.close());
    dialog.addEventListener('close', () => {
        expanded.width = expanded.height = 1;
    });
    return {
        show(value) {
            if (!value.startsWith('nostrconnect://')) throw new Error('Invalid connection link.');
            try {
                code = qrcode(0, 'M');
                code.addData(value, 'Byte');
                code.make();
                draw(thumbnail, code, 4);
                button.hidden = false;
                return true;
            } catch (_) {
                code = null;
                button.hidden = true;
                return false;
            }
        },
        clear() {
            code = null;
            if (dialog.open) dialog.close();
            thumbnail.width = thumbnail.height = expanded.width = expanded.height = 1;
            button.hidden = true;
        },
    };
}
