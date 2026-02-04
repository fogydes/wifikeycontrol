import "./style.css";
import * as App from "../wailsjs/go/main/App";
import { EventsOn } from "../wailsjs/runtime/runtime";

// State
let serverRunning = false;
let connected = false;
let deviceName = "";
let controlling = false;
let logs = [];
let localIP = "Loading...";

// Render the app
function render() {
  document.querySelector("#app").innerHTML = `
        <div class="header">
            <h1>WiFi Key Control</h1>
            <span>${connected ? "🟢 Connected" : serverRunning ? "🟡 Waiting..." : "⚪ Offline"}</span>
        </div>

        <div class="status-section">
            <div class="status-card">
                <h3>Server Status</h3>
                <div class="value">
                    <span class="status-indicator ${serverRunning ? "active" : ""}"></span>
                    ${serverRunning ? "Running on port 12346" : "Stopped"}
                </div>
                <div class="ip-display" style="margin-top:8px;font-size:12px;opacity:0.7">PC IP: ${localIP}</div>
            </div>
            <div class="status-card">
                <h3>Device</h3>
                <div class="value">
                    <span class="status-indicator ${connected ? "connected" : ""}"></span>
                    ${connected ? deviceName : "Not connected"}
                </div>
            </div>
        </div>

        <div class="controls">
            ${
              !serverRunning
                ? `<button class="btn btn-primary" id="startBtn">▶ Start Server</button>`
                : `<button class="btn btn-danger" id="stopBtn">■ Stop Server</button>`
            }
            ${serverRunning ? `<button class="btn btn-secondary" id="usbModeBtn">🔌 USB Mode</button>` : ""}
            ${
              connected && controlling
                ? `<button class="btn btn-secondary" id="returnBtn">↩ Return to PC</button>`
                : ""
            }
        </div>

        <div class="log-panel">
            <div class="log-header">
                <h3>Activity Log</h3>
                <button class="btn btn-secondary" style="flex:0;padding:6px 12px;font-size:12px" id="clearLogsBtn">Clear</button>
            </div>
            <div class="log-content" id="logContent">
                ${
                  logs.length === 0
                    ? '<div class="log-entry">No activity yet. Start the server to begin.</div>'
                    : logs
                        .map(
                          (log) =>
                            `<div class="log-entry info">${escapeHtml(log)}</div>`,
                        )
                        .join("")
                }
            </div>
        </div>

        <div class="footer">
            <span>Control Mode: ${controlling ? "Android" : "PC"}</span>
            <span>Move cursor to screen edge to switch control</span>
        </div>
    `;

  // Attach event handlers
  attachEventHandlers();

  // Scroll log to bottom
  const logContent = document.getElementById("logContent");
  if (logContent) {
    logContent.scrollTop = logContent.scrollHeight;
  }
}

function attachEventHandlers() {
  const startBtn = document.getElementById("startBtn");
  const stopBtn = document.getElementById("stopBtn");
  const returnBtn = document.getElementById("returnBtn");
  const clearLogsBtn = document.getElementById("clearLogsBtn");
  const usbModeBtn = document.getElementById("usbModeBtn");

  if (startBtn) {
    startBtn.addEventListener("click", async () => {
      try {
        await App.StartServer();
        serverRunning = true;
        addLog("Server started");
        render();
      } catch (err) {
        addLog("Failed to start server: " + err);
      }
    });
  }

  if (stopBtn) {
    stopBtn.addEventListener("click", async () => {
      try {
        await App.StopServer();
        serverRunning = false;
        connected = false;
        controlling = false;
        addLog("Server stopped");
        render();
      } catch (err) {
        addLog("Failed to stop server: " + err);
      }
    });
  }

  if (returnBtn) {
    returnBtn.addEventListener("click", async () => {
      try {
        await App.ReturnToPC();
      } catch (err) {
        addLog("Failed to return control: " + err);
      }
    });
  }

  if (clearLogsBtn) {
    clearLogsBtn.addEventListener("click", async () => {
      logs = [];
      await App.ClearLogs();
      render();
    });
  }

  if (usbModeBtn) {
    usbModeBtn.addEventListener("click", async () => {
      try {
        await App.EnableUSBMode();
        addLog("USB Mode enabled - Use 127.0.0.1 on Android");
        render();
      } catch (err) {
        addLog("USB Mode failed: " + err);
      }
    });
  }
}

function addLog(msg) {
  const timestamp = new Date().toLocaleTimeString();
  logs.push(`[${timestamp}] ${msg}`);
  if (logs.length > 100) {
    logs = logs.slice(-100);
  }
}

function escapeHtml(text) {
  const div = document.createElement("div");
  div.textContent = text;
  return div.innerHTML;
}

// Set up event listeners from Go backend
EventsOn("server:started", () => {
  serverRunning = true;
  render();
});

EventsOn("server:stopped", () => {
  serverRunning = false;
  connected = false;
  controlling = false;
  render();
});

EventsOn("device:connected", (name) => {
  connected = true;
  deviceName = name;
  addLog(`Device connected: ${name}`);
  render();
});

EventsOn("device:disconnected", () => {
  connected = false;
  deviceName = "";
  controlling = false;
  addLog("Device disconnected");
  render();
});

EventsOn("control:switched", (edge) => {
  if (edge === "return_to_pc") {
    controlling = false;
    addLog("Control returned to PC");
  } else {
    controlling = true;
    addLog(`Control switched to Android (edge: ${edge})`);
  }
  render();
});

EventsOn("log", (msg) => {
  addLog(msg);
  render();
});

// Initial render
render();

// Check initial state
(async () => {
  try {
    serverRunning = await App.IsServerRunning();
    connected = await App.IsConnected();
    localIP = await App.GetLocalIP();
    if (connected) {
      deviceName = await App.GetConnectedDevice();
      controlling = await App.IsControllingAndroid();
    }
    render();
  } catch (err) {
    console.error("Failed to get initial state:", err);
  }
})();
