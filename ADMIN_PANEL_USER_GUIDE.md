# Admin Panel Quick Start Guide

## Accessing the Admin Panel

### Step 1: Activate Secret Access
1. Simply **click** (tap) in the **top-left corner** of the screen
2. You should see the PIN authentication screen appear immediately

**Tip:** The activation zone is approximately 100dp from the top-left corner.

### Step 2: Enter PIN
1. Use the on-screen keypad to enter the PIN: **01121999**
2. Your input will be masked with dots (•) for security
3. If correct, you'll be taken to the Admin Panel
4. If incorrect, the screen will shake and input will clear

### Step 3: Navigate the Admin Panel
The admin panel has 4 main tabs:

## 🔌 Connection Tab

**Purpose:** Manage backend connectivity and network settings

**Features:**
- **Backend URL Setup**
  - Enter your backend API URL
  - Test connection with "Test Connection" button
  - View connection status (Connected/Disconnected)
  
- **WiFi Management**
  - Scan for available WiFi networks
  - View signal strength
  - Connect to networks
  
- **Admin Token Management**
  - Validate current admin token
  - Refresh token when expired
  - View token expiry time

**Common Tasks:**
```
1. Set Backend URL → Enter URL → Test Connection
2. Connect to WiFi → Scan Networks → Select Network → Connect
3. Refresh Token → Click "Refresh Token" → Wait for confirmation
```

## ⚙️ Hardware Tab

**Purpose:** Monitor and control water fountain hardware

**Features:**
- **Slot Management (1-10)**
  - View status of each slot (Operational/Error/Maintenance)
  - Enable/Disable individual slots
  - Test dispense for each slot
  
- **Global Operations**
  - Prime all dispensers
  - Reset all slots
  - Run system diagnostics
  
- **Status Indicators**
  - 🟢 Green: Operational
  - 🔴 Red: Error/Disabled
  - 🟡 Yellow: Maintenance needed

**Common Tasks:**
```
1. Test Slot → Click slot card → Test Dispense
2. Disable Faulty Slot → Click slot card → Disable
3. Run Diagnostics → Click "Run Diagnostics" → Review results
4. Prime System → Click "Prime Dispensers" → Wait for completion
```

## 📋 Logs Tab

**Purpose:** View system logs and debug information

**Features:**
- **Log Filtering**
  - ALL: Show all log levels
  - ERRORS: Critical errors only
  - WARNINGS: Warning messages
  - INFO: Informational messages
  
- **Log Management**
  - Refresh: Reload logs from system
  - Clear: Delete all logs
  - Export: Save logs to file and share
  
- **Real-time Monitoring**
  - Toggle for live log updates
  - Auto-scroll to latest entries
  
- **Log Display**
  - Color-coded by severity
  - Timestamp with milliseconds
  - Tag/category identification
  - Full message text

**Common Tasks:**
```
1. View Errors Only → Click "Errors" filter
2. Export Logs → Click "Export" → Choose share method
3. Clear Old Logs → Click "Clear" → Confirm
4. Monitor Real-time → Enable "Real-time monitoring" toggle
```

**Log Entry Format:**
```
12:34:56.789 ERROR Hardware    Slot 5 motor failure detected
[Timestamp] [Level] [Tag]      [Message]
```

## 🔧 System Tab

**Purpose:** System settings and critical operations

**Features:**
- **System Information**
  - App version number
  - System uptime
  - Last backend sync time
  
- **System Operations**
  - Restart Machine: Reboot the system
  - Factory Reset: Reset all settings to default
  - Emergency Shutdown: Immediate shutdown
  
- **Settings**
  - Change Admin Password
  - Configure auto-lock timeout
  - System preferences

**Common Tasks:**
```
1. Restart System → Click "Restart Machine" → Confirm
2. Factory Reset → Click "Factory Reset" → Confirm (CAUTION!)
3. Check Version → View system info section
4. Change Password → Click "Change Admin Password" → Enter new PIN
```

⚠️ **WARNING:** Factory Reset and Emergency Shutdown are irreversible operations. Always confirm before proceeding.

## Keyboard Shortcuts & Tips

### Navigation
- **Tab Selection:** Tap tab names at the top
- **Back Button:** Use device back button to exit
- **Confirmation Dialogs:** Read carefully before confirming destructive actions

### Best Practices
1. **Regular Log Exports:** Export logs weekly for debugging
2. **Test Connections:** Verify backend connectivity after network changes
3. **Monitor Hardware:** Check hardware status daily
4. **Keep Updated:** Note system version for support requests

### Troubleshooting

**Problem: Can't access admin panel**
- Solution: Ensure you're clicking in the top-left corner (100dp zone)
- Solution: Try tapping slightly away from the absolute corner
- Solution: Make sure it's a tap, not a swipe or long press

**Problem: PIN not working**
- Solution: Verify you're entering "01121999" exactly
- Solution: Use backspace to correct mistakes
- Solution: Try "Clear All" and re-enter

**Problem: Connection test fails**
- Solution: Check WiFi is connected
- Solution: Verify backend URL is correct
- Solution: Ensure backend server is running
- Solution: Check firewall settings

**Problem: Logs not exporting**
- Solution: Check storage permissions
- Solution: Try clearing app cache
- Solution: Ensure external storage is available

**Problem: Hardware commands not responding**
- Solution: Check hardware connections
- Solution: Run diagnostics to identify issues
- Solution: Restart the system
- Solution: Check hardware power supply

## Security Notes

### Session Management
- Admin sessions don't timeout automatically (to be implemented)
- Always exit admin panel when done
- Don't share PIN with unauthorized users

### Data Protection
- Logs may contain sensitive information
- Export logs only to secure locations
- Factory reset will delete all data permanently

### Access Control
- Only authorized personnel should access admin panel
- Change default PIN after initial setup
- Monitor admin access logs regularly

## Support Information

### Getting Help
If you encounter issues:
1. Check this guide first
2. Export and review logs for error messages
3. Note the app version from System tab
4. Document steps to reproduce the issue
5. Contact support with details

### Admin Panel Version
Current Implementation: v1.0
Documentation Date: September 29, 2025

### Feature Requests
To request new admin panel features:
1. Document the desired functionality
2. Explain the use case
3. Provide mockups if applicable
4. Submit through proper channels

## Quick Command Reference

```
ACCESS ADMIN:      Click/tap in top-left corner (100dp zone)
ENTER PIN:         0-1-1-2-1-9-9-9
EXIT:              Device back button or tab back
TEST CONNECTION:   Connection Tab → Test Connection
TEST SLOT:         Hardware Tab → Click slot → Test Dispense
VIEW ERRORS:       Logs Tab → Filter: Errors
EXPORT LOGS:       Logs Tab → Export → Choose app
RESTART:           System Tab → Restart Machine → Confirm
FACTORY RESET:     System Tab → Factory Reset → CONFIRM CAREFULLY
```

## Development & Maintenance

### Configuration Files
- PIN: `AdminAuthActivity.kt` (line ~70)
- Gesture: `AdminGestureDetector.kt`
- Backend URL: Stored in SharedPreferences
- Token: Stored in SharedPreferences

### Integration Points
- `MainActivity.kt`: Gesture detection integration
- `WaterFountainManager`: Hardware control integration (TODO)
- Backend API: Connection management (TODO)
- Logging System: Log collection integration (TODO)

### Future Enhancements
- [ ] Biometric authentication option
- [ ] Session timeout implementation
- [ ] Enhanced error reporting
- [ ] Real-time hardware monitoring
- [ ] Remote configuration management
- [ ] Multi-user admin roles
- [ ] Audit trail logging
- [ ] Scheduled maintenance alerts

---

**Remember:** With great power comes great responsibility. Use admin features carefully!
