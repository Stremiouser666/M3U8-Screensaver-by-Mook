# M3U8 Screensaver for Android TV

A enhanced screensaver application for Android TV that transforms your device into a dynamic video display. Stream live content, schedule different videos by day of the week, customize playback behavior.

---

## 📑 Table of Contents

- [Overview](#-overview)
- [Features](#-features)
- [Requirements](#-requirements)
- [Installation](#-installation)
- [Usage Guide](#-usage-guide)
- [Screenshots & Demo](#-screenshots--demo)
- [Configuration Guide](#-configuration-guide)
- [Troubleshooting](#-troubleshooting)
- [Settings Overview](#-settings-sections-overview)
- [Privacy & Permissions](#-privacy--permissions)
- [Disclaimers](#-important-disclaimers)
- [Tips & Best Practices](#-tips--best-practices)
- [Logcat & Debug Guide](#-logcat--debug-guide)
- [Bug Reports](#-bug-reports--support)
- [Changelog](#-updates--changelog)
- [License](#-license)

---

## 🎯 Overview

M3u8 Screensaver turns your Android TV into a beautiful display by playing video streams when your device is idle. Whether you want to display live broadcasts, scheduled content, or curated video feeds, this application provides professional-grade control over every aspect of the playback experience.

**Perfect for:**
- Live event viewing
- Digital signage and displays
- Background entertainment
- Content rotation by day of week

---

## ✨ Features

### Core Playback
- **Multiple Stream Formats**: Support for YouTube live videos, Rutube streams, and direct HLS (M3U8) URLs
- **Smart Stream Detection**: Automatically detects stream type and handles extraction
- **Automatic Fallback**: Gracefully falls back to default stream if URL is unavailable
- **Repeat Mode**: Seamlessly loops video playback

### Advanced Playback Control
- **Resume Playback**: Save your position and resume from exactly where you left off
- **Intro Playback**: Watch the beginning of the video before jumping to content
- **Skip Beginning**: Automatically skip the first X seconds of each video
- **Random Seek**: Jump to random positions for varied viewing experience
- **Playback Speed Control**: Adjust video speed (0.5x to 2.0x)
- **Video Scaling**: Choose how video fits your screen (scale to fit, crop to fill, or default)
- **Audio Control**: Enable/disable audio with adjustable volume levels

### Offline Support

M3u8 Screensaver includes intelligent stream caching to maintain playback during temporary network interruptions:

**How It Works:**
- **URL Caching**: Extracted stream URLs (HLS manifests) from YouTube and Rutube are cached locally for up to 5 minutes
- **Automatic Fallback**: If the original source requires re-extraction, the app uses the cached URL instead of fetching a new one
- **Network Recovery**: Seamlessly resumes playback when internet connectivity is restored

**Limitations:**
- **Internet Required**: While cached URLs enable brief offline resilience, continuous internet connection is still required for video streaming
- **No Local Storage**: Video content is not stored locally; caching applies only to stream metadata and URLs
- **Cache Duration**: Cached URLs expire after 5 minutes and require fresh extraction from the source

### Weekly Schedule
- **Day-Specific URLs**: Set different videos for each day of the week
- **Random Mode**: Randomly select from weekly videos instead of day-specific playback
- **Easy Management**: Individual clear buttons for each day plus bulk clear option
- **Flexible Setup**: Leave days empty to use default URL

### Visual Enhancements
- **Clock Overlay**: Display current time in corner with customizable size and position
- **Burn-In Protection**: Automatic pixel shifting to prevent screen burn-in
- **Time Format Options**: Choose 12-hour or 24-hour clock display
- **Crossfade Transitions**: Smooth fade effects between video loops (coming soon)

### Developer & Diagnostic Tools
- **Statistics Overlay**: Real-time playback statistics (resolution, bitrate, fps, buffer status)
- **Playback State Monitoring**: View current playback state and position
- **Debug Information**: Comprehensive logging for troubleshooting

### Stability & Reliability
- **Crash Recovery**: Automatic error handling prevents black screens
- **Memory Management**: Responds intelligently to system memory pressure
- **Network Awareness**: Detects network availability and adapts gracefully
- **Graceful Degradation**: Individual feature failures don't crash the application
- **URL Refresh**: Automatically refreshes stream URLs when playback fails
- **Stall Detection**: Monitors for playback stalls and triggers recovery

---

## 📋 Requirements

- **Device**: Android TV device (Fire TV Stick, Nvidia Shield, Sony TV, etc.)
- **OS**: Android 5.0 (API 21) or higher
- **Internet**: Connection required for streaming
- **Permissions**: Internet access, network state detection, storage access for debug logging

---

## 🚀 Installation

1. Download the latest APK from the [Releases](https://github.com/Stremiouser666/Screensaver-tv/releases) page
2. Transfer to your Android TV device or use ADB:
   ```bash
   adb install LiveScreensaver.apk
   ```
3. Grant any requested permissions
4. App appears in your TV's app launcher

---

## 📖 Usage Guide

### Initial Setup

1. **Go to Settings**
   - TV Settings → Device Preferences → Screen saver → Screen saver
   - Select "M3u8 Screensaver" from the list

2. **Enter a Stream URL**
   - Open M3u8 Screensaver Settings
   - Navigate to **Stream URL**
   - Enter your video URL (see URL Formats below)

3. **Activate Screensaver**
   - Return to TV Settings
   - Enable screensaver (typically shows when device is idle)

### Supported URL Formats

#### YouTube Live Streams ⚠️ IMPORTANT
```
https://www.youtube.com/watch?v=VIDEO_ID
```

**LIVE STREAMS ONLY**: This app supports **YouTube live streams** exclusively. Regular YouTube videos (pre-recorded content) will **NOT work**. 

**How to identify a live stream:**
- Look for a "LIVE" badge on the YouTube video thumbnail
- Check the channel's live tab to confirm active broadcast
- Live streams must be publicly accessible (not members-only)

**Why regular videos don't work:**
Regular YouTube videos use different streaming protocols that require different extraction methods. Only live HLS streams are currently supported.

#### Direct HLS Streams (M3U8)
```
https://example.com/path/to/stream.m3u8
```
Recommended for best performance and reliability.

#### Rutube Streams
```
https://rutube.ru/video/VIDEO_ID/
```
Auto-extraction automatically converts to playable format.

#### Default Test Stream
```
https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8
```
Used if no URL is provided or if selected URL fails.

---

## 🎬 Screenshots & Demo

### Settings Interface
*[Screenshot placeholder: Settings menu overview]*

### Clock Overlay
*[Screenshot placeholder: Clock display on screensaver]*

### Statistics Display
*[Screenshot placeholder: Debug stats overlay]*

### Weekly Schedule Configuration
*[Screenshot placeholder: Day-specific URL setup]*

### Demo Video
*[Video placeholder: App in action showing playback and features]*

---

## ⚙️ Configuration Guide

### Playback Control Settings

**Intro Playback**
- Enable to watch the beginning before random seeking
- Set duration (1-60 seconds) for how long intro plays
- Useful for seeing channel IDs or opening graphics

**Skip Beginning**
- Skip unwanted intro content (commercials, logos, etc.)
- Set duration (0-600 seconds) to skip from start
- Combines with Intro Playback if both enabled

**Random Seek**
- Jump to random positions for varied playback
- Creates impression of continuous content
- Disable for sequential playback from start

**Resume Playback**
- Saves your exact playback position when screensaver stops
- Automatically resumes from same position on restart
- Only works if all conditions are met:
  - Same URL is still configured
  - Resume feature is enabled in settings
  - If Random Seek is enabled: Less than 5 minutes have passed
  - If Random Seek is disabled: Works indefinitely

**Playback Speed**
- Adjust speed from 0.5x (half speed) to 2.0x (double speed)
- Creates slow-motion or fast-motion effects
- Useful for creative presentations

**Video Scaling**
- **Scale to Fit**: Entire video visible (black bars possible)
- **Scale to Fill**: Video fills screen (edges may be cropped)
- **Default**: Device default behavior

**Audio Control**
- Toggle audio on/off
- Adjust volume (0-100%)
- Useful when screensaver should be silent

### Weekly Schedule Settings

Enable schedule to show different content each day:

1. **Enable Weekly Schedule** toggle
2. Enter URLs for each day (Monday-Sunday)
3. Leave days empty to use default URL
4. Choose mode:
   - **Day-Specific**: Shows URL for current day
   - **Random Mode**: Picks random URL from all configured days and main URL

**Use Cases:**
- Corporate: Different content by day
- Retail: Time-based messaging
- Education: Rotating class information

### Clock Overlay Settings

Display time while screensaver plays:

- **Enable Clock**: Toggle clock display
- **Position**: Choose corner (top-left, top-right, bottom-left, bottom-right)
- **Size**: Adjust text size (small to large)
- **Time Format**: 12-hour (12:30 PM) or 24-hour (00:30)
- **Pixel Shift**: Prevent burn-in by moving clock position periodically

### Debug & Diagnostic Settings

**Statistics Overlay**
- Shows real-time playback metrics
- Position, duration, buffer percentage
- Video resolution, bitrate, frame rate
- Useful for troubleshooting

---

## 🔧 Troubleshooting

### Screensaver Won't Start
- Verify screensaver is enabled in TV settings
- Check device is idle for correct duration
- Restart the app

### Black Screen
- Stream may be loading (wait 10 seconds)
- Check internet connection
- Try a different URL
- Check logcat for error messages

### YouTube Videos Don't Play
- ✅ Confirm it's a **live** stream (regular videos not supported)
- ✅ Verify stream is publicly accessible
- ✅ Try the test URL first to confirm app works
- Try extracting manually in settings
- Check that video has the "LIVE" badge

### Buffering / Stalls
- Check internet connection speed
- Reduce video quality/bitrate
- Try a different stream
- Check available device storage

### Resume Not Working
- Verify "Resume Playback" is enabled in settings
- If Random Seek is enabled: Confirm less than 5 minutes have passed
- Check URL hasn't changed
- Try with same URL again

### No Audio
- Enable audio in settings under **Audio** section
- Check volume is above 0%
- Verify TV audio output is working
- Check HDMI connection

### Memory/Performance Issues
- Stats overlay updates may reduce performance (disable if needed)
- Disable clock overlay if device is slow
- Reduce update frequency in debug settings
- Restart device

---

## 📱 Settings Sections Overview

### Stream URL (Main)
- Primary video source
- Auto-detects format (YouTube/Rutube/HLS)
- Clear button to reset

### Weekly Schedule
- Enable/disable day-specific URLs
- Configure each day separately
- Random selection option
- Individual and bulk clear buttons

### Playback Control
- Intro, skip, random seek options
- Speed and scaling preferences
- Resume playback feature
- Audio settings

### Clock Overlay
- Time display with customization
- Position, size, format selection
- Burn-in prevention settings

### Visual Effects
- Crossfade transitions (future enhancement)
- Additional visual options

### Debug Info
- Statistics overlay toggle
- Display position and update frequency
- Performance monitoring

---

## 🔐 Privacy & Permissions

### Required Permissions

M3u8 Screensaver for Android TV requires the following permissions to function properly:

| Permission | Purpose |
|-----------|---------|
| **INTERNET** | Required to stream video content from remote sources |
| **ACCESS_NETWORK_STATE** | Required to check network connectivity status |
| **BIND_DREAM_SERVICE** | Required to run as a screensaver service on Android TV |
| **WRITE_EXTERNAL_STORAGE** | Required for debug logging to external storage |
| **READ_EXTERNAL_STORAGE** | Required to read debug logs from external storage |

All permissions are used solely for the app's core functionality and debugging purposes. No data is collected or transmitted beyond what is necessary for video playback.

**Data Collection**: This app does not collect any user data. All URLs and settings remain on your device.

---

## ⚠️ Important Disclaimers

### URL Disclaimer
The only pre-configured stream URL is:
- **Big Buck Bunny Test Stream**: `https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8`

This is a free, publicly available test stream for demonstration purposes. Users must provide their own stream URLs.

### Stream Compatibility
- Only **live** YouTube videos are supported (regular videos will fail)
- Rutube auto-extraction requires internet connection
- Some streams may have geographic restrictions
- Some streams may require authentication (not currently supported)
- Stream availability is not guaranteed

### Device Compatibility
- Tested on Fire TV Stick (recommended)
- Works on most Android TV devices
- Performance varies by device capabilities
- Some TV brands may have limited screensaver support

### Known Limitations
- No support for password-protected streams
- No support for DRM-protected content
- Clock overlay may appear pixelated on some TVs
- Crossfade feature not yet implemented

---

## 💡 Tips & Best Practices

1. **Test First**: Start with the Big Buck Bunny test URL before adding your own streams
2. **Use Direct URLs**: Direct M3U8 URLs work best and fastest
3. **Fallback Strategy**: YouTube/Rutube extraction requires internet; use M3U8 for reliability
4. **Scheduling**: Set up weekly schedule with a default URL as fallback
5. **Performance**: Disable stats overlay for better performance on older devices
6. **Resolution**: Lower resolution streams use less bandwidth and process power
7. **Clock Overlay**: Use pixel shift to protect your TV from burn-in if leaving on for long periods

---

## 🐛 Bug Reports & Support

Found an issue? Please report with:
- Device model and Android version
- Stream URL (or type: YouTube/Rutube/HLS)
- Error message from logcat
- Steps to reproduce
- Screenshots (if applicable)

---

## 🔍 Logcat & Debug Guide

### Enabling ADB on Your Device

**Fire TV Stick:**
1. Settings → Device → Developer options
2. Enable "ADB Debugging"
3. Note your device's IP address

**Other Android TV Devices:**
1. Settings → About → System
2. Tap Build number 7 times to unlock Developer options
3. Go to Developer options → Enable "ADB Debugging"

### Connecting via ADB

On your computer:
```bash
adb connect DEVICE_IP_ADDRESS:5555
```

Replace `DEVICE_IP_ADDRESS` with your TV's IP address.

### Viewing Logcat

**View all M3u8 Screensaver logs:**
```bash
adb logcat | grep LiveScreensaver
```

**Save logcat to file for analysis:**
```bash
adb logcat | grep LiveScreensaver > screensaver_logs.txt
```

**View last 100 log lines:**
```bash
adb logcat -n 100 | grep LiveScreensaver
```

### Common Error Messages

**"URL changed - not resuming"**
- The stream URL has changed since last playback
- Resume only works with the same URL
- Solution: Use the same URL or enable Random Seek

**"Resume timeout expired"**
- More than 5 minutes have passed with Random Seek enabled
- Resume works indefinitely with Random Seek disabled
- Solution: Check your Resume + Random Seek settings

**"No valid resume data found"**
- Resume preference is enabled but no saved position exists
- First time playing this URL
- Solution: Play the stream first, then restart screensaver

**"Playback error"**
- Stream extraction or playback failed
- Check internet connection
- Try a different URL
- Solution: Enable stats overlay to see detailed error info

**"Failed to extract Rutube URL"**
- Rutube video ID couldn't be extracted from URL
- URL format may be incorrect
- Solution: Copy URL directly from browser address bar

**"No M3U8 URL found in Rutube response"**
- Rutube API returned unexpected response
- Stream may be unavailable or restricted
- Solution: Try the stream in browser first to confirm it works

### Debug Statistics Overlay

Enable statistics overlay in settings to see:
- **Resolution**: Video dimensions (e.g., 1920x1080)
- **Bitrate**: Stream bitrate in kbps
- **FPS**: Frames per second
- **Buffer**: Buffering percentage
- **Position**: Current playback time
- **State**: IDLE, BUFFERING, READY, ENDED

This helps identify streaming issues before reporting bugs.

### Tips for Troubleshooting

1. **Check logcat first** - Most errors are logged with clear messages
2. **Check debug files** - Review logs in `/sdcard/Download/`
3. **Enable stats overlay** - Visual feedback helps identify issues
4. **Test with default URL** - Confirms app functionality
5. **Check internet speed** - Use fast.com or speedtest on another device
6. **Try different URLs** - Rules out stream-specific issues
7. **Restart device** - Clears memory and cache
8. **Save logs** - Keep logcat output and debug files for bug reports

---

## 📄 License

This project is proprietary software. See LICENSE file for complete terms and restrictions.

---

## 🙏 Acknowledgments

- Built with ExoPlayer for robust video playback
- Uses NewPipe for YouTube stream extraction
- Inspired by the need for flexible Android TV screensavers

---

## 🚀 Future Enhancements

Planned features for future releases:
- [ ] **Standalone Video Player Mode** - Use as a dedicated video player application, not just screensaver
- [ ] Crossfade transitions between loops
- [ ] Custom background images
- [ ] Authentication support for protected streams
- [ ] Multi-stream simultaneous playback
- [ ] Advanced scheduling with time-of-day options

---

## 📞 Support Resources

- **GitHub Issues**: Report bugs and request features
- **Settings Help**: Each setting includes descriptions
- **Logcat**: Enable debug stats for troubleshooting information
- **Test URL**: Use Big Buck Bunny test stream to verify functionality

---

**Enjoy your enhanced screensaver experience!** 🎬✨