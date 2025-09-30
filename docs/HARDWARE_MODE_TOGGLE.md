# Hardware Mode Toggle Feature

## Overview
This feature allows switching between Mock Serial Communicator (for testing) and Real USB Serial Communicator (for production hardware) through the Admin Panel's System tab.

## Features
- ✅ Toggle switch in Admin Panel System tab
- ✅ Mock mode for testing without physical hardware
- ✅ Real USB mode for production deployment
- ✅ Automatic device detection for USB serial devices
- ✅ Support for multiple USB-to-serial chipsets (FTDI, Prolific, CH340, CP210x)
- ✅ Persistent settings (requires app restart to apply)

## How to Use

### Switching Modes

1. **Access Admin Panel**
   - Open the app
   - Navigate to Admin Panel → System tab

2. **Toggle Hardware Mode**
   - Find "Hardware Mode" toggle switch
   - **OFF (default)**: Mock Serial Communicator - for testing
   - **ON**: Real USB Serial Communicator - for production
   
3. **Apply Changes**
   - After toggling, you'll see a toast message: "⚠️ Real/Mock Hardware Mode Enabled - Restart app to apply changes"
   - Close and restart the app for changes to take effect

### Development/Testing Mode (Mock)
- No physical hardware required
- Simulates water fountain responses
- Useful for:
  - UI/UX testing
  - Payment flow testing
  - App logic validation
  - Demonstration purposes

### Production Mode (Real USB)
- Requires physical USB serial device
- Auto-detects connected USB-to-serial adapters
- Communicates with actual VMC hardware
- Useful for:
  - Actual water dispensing
  - Hardware integration testing
  - Production deployment

## Technical Details

### Supported USB-to-Serial Chipsets
The app uses the [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) library which supports:
- FTDI FT232R, FT232H, FT2232H, FT4232H, FT230X, FT231X, FT234XD
- Prolific PL2303
- CH340/CH341
- CP210x (CP2102, CP2104, CP2105)
- CDC ACM (Abstract Control Model)

### Serial Configuration
Default settings:
- **Baud Rate**: 9600 (configurable in WaterFountainConfig)
- **Data Bits**: 8
- **Stop Bits**: 1
- **Parity**: None
- **Flow Control**: None
- **Timeout**: 1000ms

### Architecture

#### Files Modified/Created

1. **SystemFragment.kt** - Added toggle handler
   - `updateHardwareMode(enabled: Boolean)` - Saves mode to SharedPreferences
   - `loadCurrentSettings()` - Loads toggle state on fragment load

2. **UsbSerialCommunicator.kt** (NEW) - Real hardware implementation
   - Implements `SerialCommunicator` interface
   - Auto-detects USB devices
   - Handles serial communication with real hardware

3. **WaterFountainManager.kt** - Modified initialization
   - Checks `use_real_serial` preference
   - Creates appropriate communicator based on mode
   - `createMockSerialCommunicator()` - Mock implementation

4. **Dependencies**
   - Added `usb-serial-for-android:3.7.3` library
   - Added JitPack repository

5. **AndroidManifest.xml**
   - Added USB host permission

### Data Flow

```
User Toggle → SharedPreferences → App Restart
                                    ↓
                        WaterFountainManager.initialize()
                                    ↓
                    Check "use_real_serial" preference
                                    ↓
                    ┌───────────────┴───────────────┐
                    ↓                               ↓
           Mock Communicator              USB Communicator
                    ↓                               ↓
          Simulated Responses            Real Hardware I/O
```

## USB Permissions

### Android USB Host Requirements
- USB host support is optional (app won't fail on devices without USB)
- For devices with USB OTG support, the app will request permission when:
  - USB device is connected
  - First communication attempt is made

### Troubleshooting USB Permissions

If USB device is not detected:

1. **Check USB Connection**
   - Ensure USB cable is properly connected
   - Try a different USB cable (some are charge-only)
   - Verify USB OTG adapter if using a phone/tablet

2. **Check Android Permissions**
   - App should prompt for USB device permission
   - If not, go to Settings → Apps → Water Fountain → Permissions
   - Clear app data and retry

3. **Check Device Compatibility**
   - Not all Android devices support USB host mode
   - Check if your device has USB OTG support

4. **Check Logs**
   - Use Android Studio Logcat
   - Filter by "WaterFountainManager" or "UsbSerialCommunicator"
   - Look for connection errors or device detection issues

## Testing

### Mock Mode Testing
```
1. Disable "Hardware Mode" toggle
2. Restart app
3. Check logs for: "Creating Mock Serial Communicator for testing"
4. Test water dispensing - should show success without hardware
```

### Real Hardware Testing
```
1. Connect USB serial device
2. Enable "Hardware Mode" toggle
3. Restart app
4. Check logs for: "Creating USB Serial Communicator for real hardware"
5. Verify device detection in logs
6. Test water dispensing - should communicate with real hardware
```

### Verification Logs
Key log messages to look for:

**Mock Mode:**
```
I/WaterFountainManager: Serial Communicator Mode: MOCK
I/WaterFountainManager: Creating Mock Serial Communicator for testing
D/WaterFountainManager: Mock SerialCommunicator: connect() called with baud rate 9600
```

**Real Hardware Mode:**
```
I/WaterFountainManager: Serial Communicator Mode: REAL HARDWARE
I/WaterFountainManager: Creating USB Serial Communicator for real hardware
I/UsbSerialCommunicator: Found X USB serial device(s)
I/UsbSerialCommunicator: Connected to USB serial device: [device info]
```

## Configuration

### Changing Serial Parameters
Edit `WaterFountainConfig.kt` to modify:
- Baud rate
- Timeout values
- Polling intervals

### Changing USB Configuration
Edit `UsbSerialCommunicator.kt` to modify:
- Data bits
- Stop bits
- Parity
- Flow control

## Known Limitations

1. **Requires App Restart**
   - Mode changes require app restart to take effect
   - Future enhancement could add hot-swapping

2. **USB Detection**
   - Only detects devices when app starts
   - Cannot detect hot-plug events during runtime

3. **Single Device Support**
   - Currently connects to first detected USB device
   - Multiple device support could be added if needed

## Future Enhancements

Potential improvements:
- [ ] Hot-swap support without restart
- [ ] USB device selection UI (if multiple devices)
- [ ] USB hot-plug detection during runtime
- [ ] Advanced serial configuration UI
- [ ] USB device diagnostics panel
- [ ] Connection status indicator in UI

## Troubleshooting

### Issue: Toggle doesn't work
**Solution**: Make sure to restart the app after toggling

### Issue: USB device not detected
**Solutions**:
1. Check USB cable and connection
2. Verify USB OTG support on device
3. Check Android USB permissions
4. Review logs for errors

### Issue: Communication errors in real mode
**Solutions**:
1. Verify correct baud rate
2. Check cable quality
3. Ensure proper hardware wiring
4. Review VMC protocol documentation

### Issue: App crashes on USB mode
**Solutions**:
1. Check for USB permission errors in logs
2. Verify USB library compatibility
3. Test with mock mode first

## Support

For issues or questions:
1. Check logs using Android Studio Logcat
2. Review this documentation
3. Check VMC protocol documentation
4. Contact development team

## Version History

- **v1.0** (Current)
  - Initial hardware mode toggle implementation
  - Mock and USB serial communicator support
  - System tab integration
  - USB permissions handling
