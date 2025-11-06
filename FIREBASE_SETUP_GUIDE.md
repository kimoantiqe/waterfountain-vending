# Firebase Configuration & API Mode Setup Guide

**Date:** November 5, 2025

---

## 🔥 Your Firebase Projects

Based on `.firebaserc`:
- **Dev:** `waterfountain-dev` ✅ Currently configured
- **Prod:** `waterfountain-25886`

**Current App Configuration:**
- Base URL: `https://us-central1-waterfountain-dev.cloudfunctions.net`
- This is correct for dev environment! ✅

---

## 🐛 Issues Found & Fixes

### Issue 1: API Mode Toggle Not Working Correctly

**Problem:** When you toggle to "Real API" mode, the app might not be using the correct implementation or might have bugs.

**Root Cause:** Need to verify:
1. Certificate is enrolled
2. Base URL is correct
3. Endpoints match backend
4. Request format matches backend expectations

---

## 📝 Step-by-Step: Testing Real API Mode

### Step 1: Verify Backend is Running

```bash
cd /Users/karimeldegwy/Desktop/Projects/waterfountain-backend

# Check if emulator is running
firebase emulators:start

# Or deploy to dev
firebase use dev
firebase deploy --only functions
```

### Step 2: Get Actual Function URLs

```bash
# List deployed functions
firebase functions:list --project waterfountain-dev
```

**Expected URLs:**
- `https://us-central1-waterfountain-dev.cloudfunctions.net/requestOtpFn`
- `https://us-central1-waterfountain-dev.cloudfunctions.net/verifyOtpFn`
- `https://us-central1-waterfountain-dev.cloudfunctions.net/enrollMachineKey`

### Step 3: Test Backend Directly (Optional)

```bash
# Test requestOtp endpoint
curl -X POST \
  https://us-central1-waterfountain-dev.cloudfunctions.net/requestOtpFn \
  -H 'Content-Type: application/json' \
  -d '{
    "data": {
      "phone": "+12345678900",
      "_cert": "-----BEGIN CERTIFICATE-----...",
      "_timestamp": "1699200000000",
      "_nonce": "abc123...",
      "_signature": "signature..."
    }
  }'
```

---

## 🔧 Configuration Options

### Option A: Use Firebase Emulator (Recommended for Development)

**Benefits:**
- No internet required
- Fast testing
- Free
- Easy debugging

**Setup:**

1. **Update AuthModule.kt:**
