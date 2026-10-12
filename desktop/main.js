// نسخة الكمبيوتر من إبتكار: نفس index.html جوّه شباك برنامج عادي (Electron)،
// ونفس قاعدة بيانات Firebase، فالبيانات متزامنة مع الموبايلات.
const { app, BrowserWindow, protocol, net, shell } = require('electron');
const path = require('path');
const { pathToFileURL } = require('url');

const WWW = path.join(__dirname, 'www');

// the page is served from app://ibtkar/ — a fixed, secure origin, so saved data (localStorage) stays put
protocol.registerSchemesAsPrivileged([
  { scheme: 'app', privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: true, stream: true } }
]);

if (!app.requestSingleInstanceLock()) {
  app.quit();
}

let win = null;

function createWindow() {
  win = new BrowserWindow({
    width: 1280,
    height: 860,
    minWidth: 400,
    minHeight: 500,
    title: 'إبتكار',
    icon: path.join(WWW, 'icon-512.png'),
    backgroundColor: '#F5F1EC',
    autoHideMenuBar: true,
    webPreferences: { contextIsolation: true, nodeIntegration: false, spellcheck: false }
  });

  // links to the outside world (WhatsApp, Facebook, maps...) open in the normal browser
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^(https?|mailto|tel|whatsapp):/i.test(url)) {
      shell.openExternal(url);
      return { action: 'deny' };
    }
    // images / files opened from inside the app get their own small window
    return { action: 'allow', overrideBrowserWindowOptions: { autoHideMenuBar: true, backgroundColor: '#2E2A2C' } };
  });
  win.webContents.on('will-navigate', (e, url) => {
    if (!url.startsWith('app://')) {
      e.preventDefault();
      if (/^(https?|mailto|tel|whatsapp):/i.test(url)) shell.openExternal(url);
    }
  });

  win.loadURL('app://ibtkar/index.html');
}

app.on('second-instance', () => {
  if (win) {
    if (win.isMinimized()) win.restore();
    win.focus();
  }
});

app.whenReady().then(() => {
  protocol.handle('app', (request) => {
    const { pathname } = new URL(request.url);
    const file = path.normalize(path.join(WWW, decodeURIComponent(pathname)));
    if (!file.startsWith(WWW)) return new Response('Not found', { status: 404 });
    return net.fetch(pathToFileURL(file).toString());
  });
  createWindow();
});

app.on('window-all-closed', () => app.quit());
