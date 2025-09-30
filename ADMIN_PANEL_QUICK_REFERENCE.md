# Admin Panel - Quick Reference Card

## 🔐 Access Credentials
**Gesture:** Single click in top-left corner (100dp zone)  
**PIN:** 01121999  

## 🎯 Main Features

### Connection Tab
- **Backend URL:** Configure API endpoint
- **Test Connection:** Verify backend connectivity  
- **WiFi:** Scan and connect to networks
- **Token Management:** Validate and refresh admin tokens

### Hardware Tab  
- **Slot Management:** Enable/disable individual slots (1-10)
- **Test Dispense:** Test water dispensing for each slot
- **Diagnostics:** Run full system diagnostics
- **Prime System:** Initialize all dispensers
- **Emergency Stop:** Immediate shutdown

### Logs Tab
- **Filters:** All / Errors / Warnings / Info / Debug
- **Actions:** Refresh / Clear / Export
- **Real-time:** Toggle live log monitoring
- **Export:** Share logs via email, Drive, etc.

### System Tab
- **Info:** Version, uptime, last sync
- **Restart:** Reboot the system
- **Factory Reset:** Reset to defaults (CAUTION!)
- **Emergency Shutdown:** Immediate power off
- **Settings:** Change password, configure auto-lock

## 🚨 Critical Operations

**Factory Reset:**  
❗ DELETES ALL DATA - Requires confirmation

**Emergency Shutdown:**  
❗ IMMEDIATE STOP - Use only in emergencies

**Clear Logs:**  
⚠️ Cannot be undone - Export first if needed

## 🔧 Troubleshooting

**Can't access admin panel:**
- Ensure you're clicking in the top-left corner (100dp zone)
- Click must be within the activation area
- Try clicking slightly away from the absolute corner

**PIN not working:**
- Verify you're entering exactly: 01121999
- Use backspace to correct mistakes
- Try "Clear All" and re-enter

**Connection test fails:**
- Check WiFi connection
- Verify backend URL is correct
- Ensure backend server is running

**Logs not exporting:**
- Check storage permissions
- Ensure external storage available
- Try clearing app cache

## 📞 Support

**Log Export:** Use Logs tab → Export button  
**System Info:** Check System tab for version  
**Diagnostics:** Hardware tab → Run Diagnostics  

## 🔑 Default Configuration

| Setting | Default Value |
|---------|---------------|
| PIN | 01121999 |
| Water Slot | 1 |
| Baud Rate | 115200 |
| Command Timeout | 5000ms |
| Polling Interval | 500ms |
| Max Attempts | 20 |
| Auto Clear Faults | Enabled |

## 📱 Gesture Cheat Sheet

```
┌─────────────────┐
│ [TAP]          │  ← Simple click in
│                │     top-left corner
│  100dp zone    │     (100dp from edges)
│                │
│                │
│                │
│   Main Screen  │
│                │
│                │
└─────────────────┘
```

## 🎨 Status Indicators

🟢 **Green** - Operational / Connected  
🔴 **Red** - Error / Disconnected  
🟡 **Yellow** - Warning / Maintenance  
⚪ **Gray** - Unknown / Not tested  

## ⚡ Quick Actions

| Task | Navigation |
|------|------------|
| Test slot | Hardware → Select slot → Test |
| View errors | Logs → Filter: Errors |
| Export logs | Logs → Export button |
| Check connection | Connection → Test Connection |
| Restart system | System → Restart Machine |
| Run diagnostics | Hardware → Run Diagnostics |

---

**Remember:** With great power comes great responsibility!  
Always double-check before performing critical operations.

*Version 1.0 - September 29, 2025*
