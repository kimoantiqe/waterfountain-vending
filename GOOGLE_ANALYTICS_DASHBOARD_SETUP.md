# Google Analytics Dashboard Setup Guide

## Overview
This guide walks you through setting up Google Analytics 4 (GA4) dashboards for your Water Fountain vending machine analytics system.

## Prerequisites
- Firebase project with Google Analytics enabled
- Android app already sending events (we just updated the code)
- Access to Firebase Console and Google Analytics

---

## Phase 1: Configure Custom Dimensions & Metrics

### Step 1: Access GA4 Configuration
1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Select your project: `waterfountain-vending`
3. Click **Analytics** in left sidebar
4. Click **"Go to Google Analytics"** button (top right)
5. In GA4, click **Admin** (gear icon, bottom left)
6. Under **Property** column, click **Custom definitions**

### Step 2: Create Custom Dimensions
Click **"Create custom dimension"** for each of the following:

| Dimension Name | Event Parameter | Scope | Description |
|---|---|---|---|
| `screen_name` | `screen_name` | Event | Which screen the event occurred on |
| `machine_id` | `machine_id` | User | Which machine the user interacted with |
| `device_model` | `device_model` | Event | Device model of the vending machine |
| `time_of_day` | `time_of_day` | Event | Time segment (morning/afternoon/evening/night) |
| `day_of_week` | `day_of_week` | Event | Day of week (monday, tuesday, etc.) |
| `slot_number` | `slot_number` | Event | Which slot was used for dispensing |

**How to create each dimension:**
1. Click **Create custom dimension**
2. Enter **Dimension name** (from table above)
3. Select **Scope** (Event or User)
4. Enter **Event parameter** (from table above)
5. Enter **Description**
6. Click **Save**

### Step 3: Create Custom Metrics
Click **"Create custom metric"** for each of the following:

| Metric Name | Event Parameter | Measurement Unit | Description |
|---|---|---|---|
| `screen_duration_ms` | `screen_duration_ms` | Standard (ms) | Time spent on a screen |
| `total_journey_duration_ms` | `total_journey_duration_ms` | Standard (ms) | Total journey from start to water pickup |
| `dispense_duration_ms` | `dispense_duration_ms` | Standard (ms) | Time from dispense start to completion |
| `time_to_complete_ms` | `time_to_complete_ms` | Standard (ms) | Time to complete a task |
| `phone_length` | `phone_length` | Standard | Length of phone number entered |

**How to create each metric:**
1. Click **Create custom metric**
2. Enter **Metric name** (from table above)
3. Enter **Event parameter** (from table above)
4. Select **Measurement unit**: Standard
5. Enter **Description**
6. Click **Save**

---

## Phase 2: Create Exploration Reports

### Report 1: User Journey Funnel

**Purpose:** Track drop-off at each step of the user journey

1. In GA4, click **Explore** (left sidebar)
2. Click **Blank** template
3. Name report: `User Journey Funnel`

**Configuration:**
- **Technique:** Funnel exploration
- **Steps** (in order):
  1. `app_opened` (Start point)
  2. `tap_to_start_clicked`
  3. `phone_number_completed`
  4. `consent_accepted`
  5. `sms_sent_success`
  6. `otp_verified_success`
  7. `dispensing_started` (Goal!)

- **Breakdown Dimensions:**
  - `machine_id`
  - `time_of_day`
  - `day_of_week`

- **Time window:** 30 minutes (users must complete journey within 30 min)

**Visualizations:**
- Funnel chart (default)
- Table showing completion rates per dimension

---

### Report 2: Per-Machine Performance

**Purpose:** Compare metrics across different machines

1. Click **Explore** → **Blank**
2. Name report: `Per-Machine Performance`

**Configuration:**
- **Technique:** Free form
- **Dimensions:** `machine_id`
- **Metrics:**
  - `Event count` (filter by `dispensing_started`)
  - `Total users`
  - `Average session duration`
  - Custom metric: `total_journey_duration_ms` (avg)

- **Breakdown:** `day_of_week`
- **Date range:** Last 30 days

**Visualizations:**
- Bar chart: Machine ID vs Dispenses
- Table: Detailed metrics per machine
- Line chart: Dispenses over time per machine

---

### Report 3: Help-Seeking Behavior (FAQ Analysis)

**Purpose:** Identify which screens confuse users (high FAQ clicks)

1. Click **Explore** → **Blank**
2. Name report: `FAQ Analysis - User Confusion Points`

**Configuration:**
- **Technique:** Free form
- **Dimensions:**
  - `screen_name`
  - `event_name` (filter: `faq_opened`, `faq_closed`)

- **Metrics:**
  - `Event count` (for `faq_opened`)
  - Custom metric: `duration_ms` (for `faq_closed` - time spent reading)
  - Total users

- **Filters:**
  - Events: `faq_opened` OR `faq_closed`

**Visualizations:**
- Bar chart: Screen name vs FAQ opens
- Table: Screen, FAQ Opens, Avg Duration, % of users who opened FAQ

**Key Insights:**
- High FAQ open rate = confusing screen → needs UX improvements
- Long FAQ duration = complex issue → needs clearer explanation

---

### Report 4: Campaign QR Engagement

**Purpose:** Track QR code scans from advertiser cans (this data comes from backend)

**Note:** This requires backend implementation (Phase 3) to be complete.

1. Click **Explore** → **Blank**
2. Name report: `QR Campaign Performance`

**Configuration:**
- **Data Source:** BigQuery export (see Phase 3)
- **Dimensions:**
  - `campaign_id`
  - `can_design_id`
  - `machine_id`

- **Metrics:**
  - QR scans
  - Unique users who scanned
  - URL clicks after scan
  - Scan-to-click conversion rate

**Visualizations:**
- Sankey diagram: Machine → QR Scan → URL Click
- Table: Campaign performance metrics
- Map: Geographic distribution of scans (if location data available)

---

### Report 5: Operational Health Dashboard

**Purpose:** Monitor machine health and uptime

**Note:** This requires backend implementation (Phase 3) and BigQuery (Phase 3).

1. Click **Explore** → **Blank**
2. Name report: `Machine Health & Uptime`

**Configuration:**
- **Data Source:** BigQuery (custom query)
- **Dimensions:**
  - `machine_id`
  - Date/time

- **Metrics:**
  - Uptime percentage
  - Total dispenses
  - Successful dispenses
  - Failed dispenses
  - Error codes (breakdown)

**Visualizations:**
- Gauge: Uptime % per machine
- Line chart: Dispenses over time
- Table: Error codes and frequency

---

### Report 6: Drop-off Analysis (Abandonment Insights)

**Purpose:** Understand why users abandon the journey

1. Click **Explore** → **Blank**
2. Name report: `User Abandonment Analysis`

**Configuration:**
- **Technique:** Free form
- **Event:** `user_abandoned`
- **Dimensions:**
  - `screen_name` (where they abandoned)
  - `reason` (timeout, return_to_main, etc.)
  - `time_of_day`

- **Metrics:**
  - `Event count`
  - Custom metric: `screen_duration_ms` (how long before abandoning)
  - Total users

**Visualizations:**
- Stacked bar chart: Screen × Abandonment reason
- Table: Screen, Reason, Count, Avg Duration
- Heatmap: Time of day × Screen abandonment

**Key Insights:**
- If users abandon quickly (low `screen_duration_ms`) → confusing UI
- If users abandon after long time → lost interest or distraction
- High timeout rate → inactivity timer too aggressive

---

## Phase 3: Real-Time Monitoring

### Create Real-Time Report

1. In GA4, click **Reports** (left sidebar)
2. Click **Realtime**
3. Customize cards:

**Add Cards:**
- **Users by screen:** Shows live user flow
- **Event count by name:** Shows which events are firing
- **Events per minute:** Shows activity peaks

**Use Case:**
- Test analytics implementation
- Monitor live machine performance
- Debug event tracking issues

**Testing Commands:**
Enable debug mode in Android app:
```bash
# Enable Firebase Analytics Debug Mode
adb shell setprop debug.firebase.analytics.app com.waterfountainmachine.app

# View events in real-time
adb logcat -s AnalyticsManager
```

---

## Phase 4: Set Up Alerts

### Create Alert for Machine Failures

1. In GA4 Admin → **Custom alerts**
2. Click **Create custom alert**

**Alert: No Dispenses in 4 Hours**
- **Name:** `Machine Inactive - No Dispenses`
- **Condition:** `Event count` (filter: `dispensing_started`) < 1
- **Time period:** Last 4 hours
- **Notify:** Your email
- **Frequency:** Once per day

**Alert: High Failure Rate**
- **Name:** `High Dispense Failure Rate`
- **Condition:** `Event count` (filter: `vending_failed`) > 5
- **Time period:** Last 1 hour
- **Notify:** Your email + SMS
- **Frequency:** Immediately

---

## Phase 5: Share Dashboards with Investors

### Create Investor-Ready Summary Report

1. Click **Explore** → **Blank**
2. Name: `Executive Summary - Investor Metrics`

**Key Metrics to Show:**
- **Total Dispenses** (Last 30 days)
- **Total Unique Users** (Last 30 days)
- **Average Journey Duration** (Target: <90 seconds)
- **Completion Rate** (% who reach `dispensing_started`)
- **Machine Uptime %** (Target: >95%)

**Visualizations:**
- **Big Number Cards:** Key metrics at top
- **Line Chart:** Dispenses over time (shows growth)
- **Bar Chart:** Dispenses per machine (shows scalability)
- **Funnel:** Conversion rate per step

### Share the Report:
1. Click **Share** (top right)
2. Enter investor email addresses
3. Set permission: **Viewer** (read-only)
4. Click **Send**

**Export Options:**
- **PDF Export:** File → Download → PDF
- **Google Slides:** Copy charts to presentation
- **Scheduled Email:** Set up in GA4 to auto-send weekly

---

## Phase 6: Advanced Setup (Optional)

### Integration with Google Data Studio (Looker Studio)

For more customizable dashboards:

1. Go to [Looker Studio](https://lookerstudio.google.com/)
2. Click **Create** → **Data Source**
3. Select **Google Analytics 4**
4. Authorize and select your property
5. Create custom dashboards with full design control

**Benefits:**
- Beautiful, branded dashboards
- Combine GA4 + BigQuery + Firestore data
- Share public links with investors
- Embed dashboards in admin panel

---

## Testing Your Setup

### Verify Events are Flowing

1. **Check Firebase Console:**
   - Firebase Console → Analytics → Events
   - Should see all events (app_opened, tap_to_start_clicked, etc.)
   - Click on event to see parameters

2. **Check GA4 Realtime:**
   - GA4 → Reports → Realtime
   - Use the app and watch events appear live

3. **Check Custom Dimensions:**
   - GA4 → Explore → Create blank report
   - Add dimension: `screen_name`
   - Should see values: "main_screen", "phone_entry_screen", etc.

### Debug Mode Commands

```bash
# Enable debug mode (events appear immediately)
adb shell setprop debug.firebase.analytics.app com.waterfountainmachine.app

# Disable debug mode
adb shell setprop debug.firebase.analytics.app .none.

# View analytics logs
adb logcat -s FA FA-SVC
```

---

## Troubleshooting

### Events not appearing in GA4
- **Wait 24-48 hours:** Non-debug events have delay
- **Enable debug mode:** See events immediately
- **Check Firebase Console:** Events → DebugView (with debug mode enabled)

### Custom dimensions showing "(not set)"
- **Wait for data:** Takes 24 hours after creation
- **Check parameter names:** Must match exactly (case-sensitive)
- **Re-send events:** Old events won't have new dimensions

### Funnel not working
- **Check time window:** Default is 30 minutes - adjust if needed
- **Verify event names:** Must match exactly
- **Check event order:** Users must trigger events in sequence

---

## Next Steps

After completing this setup:

1. ✅ **Phase 1 Complete:** Android app tracking implemented
2. ✅ **Phase 2 Complete:** GA4 dashboards configured
3. ⏳ **Phase 3:** Implement backend functions (next step)
4. ⏳ **Phase 4:** Set up BigQuery export and views

---

## Summary of Dashboard URLs

Bookmark these for quick access:

- **GA4 Home:** https://analytics.google.com/
- **Firebase Console:** https://console.firebase.google.com/
- **Real-time View:** GA4 → Reports → Realtime
- **Custom Funnels:** GA4 → Explore → User Journey Funnel
- **Debug View:** Firebase → Analytics → DebugView

---

## Key Metrics Reference

### Success Criteria (Targets)
- **Journey Completion Rate:** >75% (users who reach dispensing_started)
- **Average Journey Duration:** <90 seconds
- **Machine Uptime:** >95%
- **FAQ Open Rate:** <10% (lower = clearer UX)
- **Error Rate:** <5% of dispenses

### Red Flags (Alerts)
- No dispenses for 4+ hours
- Completion rate drops below 50%
- FAQ opens spike >20%
- Any machine <80% uptime
- Error rate >10%

---

**Setup Complete!** 🎉

Your analytics dashboards are now ready. Continue to Phase 3: Backend Implementation.
