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
let discoveredDevices = [];
let edgeThreshold = 5;

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

        ${
          discoveredDevices.length > 0
            ? `
        <div class="discovered-devices">
            <h3>Discovered Devices</h3>
            <div class="device-list">
                ${discoveredDevices
                  .map(
                    (d) =>
                      `<div class="device-item">${escapeHtml(d.name)} <span class="device-ip">${d.ip}</span></div>`,
                  )
                  .join("")}
            </div>
        </div>
        `
            : ""
        }

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

        <div class="settings-section">
            <h3>Settings</h3>
            <div class="setting-row">
                <label for="edgeThreshold">Edge Threshold: ${edgeThreshold}px</label>
                <input type="range" id="edgeThreshold" min="1" max="50" value="${edgeThreshold}" />
            </div>
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
                    : logs.map((log) => formatLogEntry(log)).join("")
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

function formatLogEntry(log) {
  let level = "info";
  const lowerLog = log.toLowerCase();
  if (
    lowerLog.includes("error") ||
    lowerLog.includes("failed") ||
    lowerLog.includes("disconnect")
  ) {
    level = "error";
  } else if (lowerLog.includes("warn") || lowerLog.includes("warning")) {
    level = "warn";
  }
  return `<div class="log-entry ${level}">${escapeHtml(log)}</div>`;
}

function attachEventHandlers() {
  const startBtn = document.getElementById("startBtn");
  const stopBtn = document.getElementById("stopBtn");
  const returnBtn = document.getElementById("returnBtn");
  const clearLogsBtn = document.getElementById("clearLogsBtn");
  const usbModeBtn = document.getElementById("usbModeBtn");
  const edgeThresholdInput = document.getElementById("edgeThreshold");

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
        discoveredDevices = [];
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

  if (edgeThresholdInput) {
    edgeThresholdInput.addEventListener("input", async (e) => {
      edgeThreshold = parseInt(e.target.value);
      // Update label in real-time
      const label = document.querySelector('label[for="edgeThreshold"]');
      if (label) label.textContent = `Edge Threshold: ${edgeThreshold}px`;
    });
    edgeThresholdInput.addEventListener("change", async (e) => {
      edgeThreshold = parseInt(e.target.value);
      try {
        await App.SetEdgeThreshold(edgeThreshold);
      } catch (err) {
        addLog("Failed to set edge threshold: " + err);
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
  discoveredDevices = [];
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

EventsOn("device:discovered", (data) => {
  // Add to discovered devices if not already present
  const existing = discoveredDevices.find((d) => d.ip === data.ip);
  if (!existing) {
    discoveredDevices.push({ ip: data.ip, name: data.name });
    addLog(`Discovered: ${data.name} (${data.ip})`);
    render();
  }
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

EventsOn("control:returned", () => {
  controlling = false;
  addLog("Control returned from Android");
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
    edgeThreshold = await App.GetEdgeThreshold();
    if (connected) {
      deviceName = await App.GetConnectedDevice();
      controlling = await App.IsControllingAndroid();
    }
    render();
  } catch (err) {
    console.error("Failed to get initial state:", err);
  }
})();
