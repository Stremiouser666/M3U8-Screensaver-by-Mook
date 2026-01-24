package com.livescreensaver.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.leanback.preference.LeanbackPreferenceFragmentCompat
import androidx.leanback.preference.LeanbackSettingsFragmentCompat
import androidx.preference.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SettingsActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : LeanbackSettingsFragmentCompat() {
        override fun onPreferenceStartInitialScreen() {
            startPreferenceFragment(PrefsFragment())
        }

        override fun onPreferenceStartFragment(
            caller: PreferenceFragmentCompat,
            pref: Preference
        ): Boolean = false

        override fun onPreferenceStartScreen(
            caller: PreferenceFragmentCompat,
            pref: PreferenceScreen
        ): Boolean {
            val fragment = PrefsFragment()
            val args = Bundle(1)
            args.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, pref.key)
            fragment.arguments = args
            startPreferenceFragment(fragment)
            return true
        }
    }

    class PrefsFragment : LeanbackPreferenceFragmentCompat() {

        companion object {
            private const val TAG = "SettingsActivity"
            private const val PREFS_NAME = "stream_cache_prefs"
            private const val KEY_ORIGINAL_URL = "original_url"
            private const val KEY_EXTRACTED_URL = "extracted_url"
            private const val KEY_URL_TYPE = "url_type"
        }

        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        private lateinit var youtubeExtractor: YouTubeStandaloneExtractor

        private val handler = Handler(Looper.getMainLooper())
        private var lastProcessedUrl = ""

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val context = preferenceManager.context
            val screen = preferenceManager.createPreferenceScreen(context)

            youtubeExtractor = YouTubeStandaloneExtractor(requireContext(), httpClient)

            // === MAIN URL SECTION ===
            val videoUrlPref = EditTextPreference(context).apply {
                key = "video_url"
                title = getString(R.string.pref_video_url_title)
                setDefaultValue(LiveScreensaverService.DEFAULT_VIDEO_URL)
                setOnBindEditTextListener { editText ->
                    editText.setSingleLine(false)
                    editText.setLines(3)
                }
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { preference ->
                    val isDisabled = preferenceManager.sharedPreferences?.getBoolean("disable_video_url", false) ?: false
                    val suffix = if (isDisabled) " (DISABLED)" else ""
                    (preference.text?.takeIf { it.isNotEmpty() } 
                        ?: getString(R.string.pref_video_url_summary)) + suffix
                }

                setOnPreferenceChangeListener { _, newValue ->
                    val url = newValue.toString().trim()

                    if (url != lastProcessedUrl) {
                        lastProcessedUrl = url

                        when {
                            isRutubeUrl(url) -> {
                                handler.postDelayed({
                                    autoExtractRutube(url, this)
                                }, 500)
                            }
                            isYouTubeUrl(url) -> {
                                handler.postDelayed({
                                    autoExtractYouTube(url, this)
                                }, 500)
                            }
                        }
                    }
                    true
                }
            }
            screen.addPreference(videoUrlPref)

            // Disable Main URL Checkbox
            val disableMainUrlPref = SwitchPreference(context).apply {
                key = "disable_video_url"
                title = getString(R.string.pref_disable_url_title)
                summary = "Disable main URL from playback"
                setDefaultValue(false)

                setOnPreferenceChangeListener { _, _ ->
                    true
                }
            }
            screen.addPreference(disableMainUrlPref)

            // Clear URL Button
            val clearUrlPref = Preference(context).apply {
                key = "clear_url_button"
                title = getString(R.string.pref_clear_url_title)
                summary = getString(R.string.pref_clear_url_summary)

                setOnPreferenceClickListener {
                    videoUrlPref.text = ""

                    val cachePrefs = requireContext().getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    cachePrefs.edit()
                        .remove(KEY_ORIGINAL_URL)
                        .remove(KEY_EXTRACTED_URL)
                        .remove(KEY_URL_TYPE)
                        .apply()

                    showToast(getString(R.string.toast_url_cleared))
                    true
                }
            }
            screen.addPreference(clearUrlPref)

            // === WEEKLY SCHEDULE SECTION ===
            val scheduleCategory = PreferenceCategory(context).apply {
                key = "category_schedule"
                title = getString(R.string.pref_category_schedule)
            }
            screen.addPreference(scheduleCategory)

            val scheduleEnabledPref = SwitchPreference(context).apply {
                key = "schedule_enabled"
                title = getString(R.string.pref_schedule_enabled_title)
                summary = getString(R.string.pref_schedule_enabled_summary)
                setDefaultValue(false)

                setOnPreferenceChangeListener { _, newValue ->
                    updateScheduleVisibility(newValue as Boolean)
                    true
                }
            }
            scheduleCategory.addPreference(scheduleEnabledPref)

            val scheduleRandomPref = SwitchPreference(context).apply {
                key = "schedule_random_mode"
                title = getString(R.string.pref_schedule_random_title)
                summary = getString(R.string.pref_schedule_random_summary)
                setDefaultValue(false)
                isVisible = scheduleEnabledPref.isChecked
            }
            scheduleCategory.addPreference(scheduleRandomPref)

            val days = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
            val dayTitles = listOf(
                R.string.pref_url_monday_title,
                R.string.pref_url_tuesday_title,
                R.string.pref_url_wednesday_title,
                R.string.pref_url_thursday_title,
                R.string.pref_url_friday_title,
                R.string.pref_url_saturday_title,
                R.string.pref_url_sunday_title
            )
            val dayClearTitles = listOf(
                R.string.pref_clear_monday_title,
                R.string.pref_clear_tuesday_title,
                R.string.pref_clear_wednesday_title,
                R.string.pref_clear_thursday_title,
                R.string.pref_clear_friday_title,
                R.string.pref_clear_saturday_title,
                R.string.pref_clear_sunday_title
            )

            days.forEachIndexed { index, day ->
                val dayUrlPref = EditTextPreference(context).apply {
                    key = "url_$day"
                    title = getString(dayTitles[index])
                    summary = getString(R.string.pref_url_day_summary)
                    setOnBindEditTextListener { editText ->
                        editText.setSingleLine(false)
                        editText.setLines(3)
                    }
                    isVisible = scheduleEnabledPref.isChecked

                    summaryProvider = Preference.SummaryProvider<EditTextPreference> { preference ->
                        val isDisabled = preferenceManager.sharedPreferences?.getBoolean("disable_url_$day", false) ?: false
                        val suffix = if (isDisabled) " (DISABLED)" else ""
                        (preference.text?.takeIf { it.isNotEmpty() } 
                            ?: getString(R.string.pref_url_day_summary)) + suffix
                    }

                    setOnPreferenceChangeListener { _, newValue ->
                        val url = newValue.toString().trim()

                        when {
                            isRutubeUrl(url) -> {
                                handler.postDelayed({
                                    autoExtractRutube(url, this)
                                }, 500)
                            }
                            isYouTubeUrl(url) -> {
                                handler.postDelayed({
                                    autoExtractYouTube(url, this)
                                }, 500)
                            }
                        }
                        true
                    }
                }
                scheduleCategory.addPreference(dayUrlPref)

                val disableDayPref = SwitchPreference(context).apply {
                    key = "disable_url_$day"
                    title = "Disable ${day.capitalize()}"
                    summary = "Skip this day's URL"
                    setDefaultValue(false)
                    isVisible = scheduleEnabledPref.isChecked

                    setOnPreferenceChangeListener { _, _ ->
                        true
                    }
                }
                scheduleCategory.addPreference(disableDayPref)

                val clearDayPref = Preference(context).apply {
                    key = "clear_${day}_button"
                    title = getString(dayClearTitles[index])
                    summary = getString(R.string.toast_day_url_cleared)
                    isVisible = scheduleEnabledPref.isChecked

                    setOnPreferenceClickListener {
                        dayUrlPref.text = ""
                        showToast(getString(R.string.toast_day_url_cleared))
                        true
                    }
                }
                scheduleCategory.addPreference(clearDayPref)
            }

            val clearSchedulePref = Preference(context).apply {
                key = "clear_schedule_button"
                title = getString(R.string.pref_clear_schedule_title)
                summary = getString(R.string.pref_clear_schedule_summary)
                isVisible = scheduleEnabledPref.isChecked

                setOnPreferenceClickListener {
                    days.forEach { day ->
                        preferenceManager.sharedPreferences?.edit()?.remove("url_$day")?.apply()
                    }
                    showToast(getString(R.string.toast_schedule_cleared))
                    true
                }
            }
            scheduleCategory.addPreference(clearSchedulePref)

            // === PLAYBACK CONTROL SECTION ===
            val playbackCategory = PreferenceCategory(context).apply {
                key = "category_playback"
                title = getString(R.string.pref_category_playback)
            }
            screen.addPreference(playbackCategory)

            val introEnabledPref = SwitchPreference(context).apply {
                key = "intro_enabled"
                title = getString(R.string.pref_intro_enabled_title)
                summary = getString(R.string.pref_intro_enabled_summary)
                setDefaultValue(true)

                setOnPreferenceChangeListener { _, newValue ->
                    updateIntroVisibility(newValue as Boolean)
                    true
                }
            }
            playbackCategory.addPreference(introEnabledPref)

            val introDurationPref = EditTextPreference(context).apply {
                key = "intro_duration"
                title = getString(R.string.pref_intro_duration_title)
                summary = getString(R.string.pref_intro_duration_summary)
                setDefaultValue("7")
                isVisible = introEnabledPref.isChecked

                setOnBindEditTextListener { editText ->
                    editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                }
            }
            playbackCategory.addPreference(introDurationPref)

            val skipBeginningEnabledPref = SwitchPreference(context).apply {
                key = "skip_beginning_enabled"
                title = getString(R.string.pref_skip_beginning_enabled_title)
                summary = getString(R.string.pref_skip_beginning_enabled_summary)
                setDefaultValue(false)

                setOnPreferenceChangeListener { _, newValue ->
                    updateSkipBeginningVisibility(newValue as Boolean)
                    true
                }
            }
            playbackCategory.addPreference(skipBeginningEnabledPref)

            val skipBeginningDurationPref = EditTextPreference(context).apply {
                key = "skip_beginning_duration"
                title = getString(R.string.pref_skip_beginning_duration_title)
                summary = getString(R.string.pref_skip_beginning_duration_summary)
                setDefaultValue("0")
                isVisible = skipBeginningEnabledPref.isChecked

                setOnBindEditTextListener { editText ->
                    editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                }
            }
            playbackCategory.addPreference(skipBeginningDurationPref)

            val randomSeekEnabledPref = SwitchPreference(context).apply {
                key = "random_seek_enabled"
                title = getString(R.string.pref_random_seek_enabled_title)
                summary = getString(R.string.pref_random_seek_enabled_summary)
                setDefaultValue(true)
            }
            playbackCategory.addPreference(randomSeekEnabledPref)

            val videoScalingPref = ListPreference(context).apply {
                key = "video_scaling_mode"
                title = getString(R.string.pref_video_scaling_title)
                summary = getString(R.string.pref_video_scaling_summary)
                entries = resources.getStringArray(R.array.video_scaling_entries)
                entryValues = resources.getStringArray(R.array.video_scaling_values)
                setDefaultValue("scale_to_fit")
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }
            playbackCategory.addPreference(videoScalingPref)

            val speedEnabledPref = SwitchPreference(context).apply {
                key = "speed_enabled"
                title = getString(R.string.pref_speed_enabled_title)
                summary = getString(R.string.pref_speed_enabled_summary)
                setDefaultValue(false)

                setOnPreferenceChangeListener { _, newValue ->
                    updateSpeedVisibility(newValue as Boolean)
                    true
                }
            }
            playbackCategory.addPreference(speedEnabledPref)

            val playbackSpeedPref = ListPreference(context).apply {
                key = "playback_speed"
                title = getString(R.string.pref_playback_speed_title)
                summary = getString(R.string.pref_playback_speed_summary)
                entries = resources.getStringArray(R.array.playback_speed_entries)
                entryValues = resources.getStringArray(R.array.playback_speed_values)
                setDefaultValue("1.0")
                isVisible = speedEnabledPref.isChecked
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }
            playbackCategory.addPreference(playbackSpeedPref)

            val resumeEnabledPref = SwitchPreference(context).apply {
                key = "resume_enabled"
                title = getString(R.string.pref_resume_enabled_title)
                summary = getString(R.string.pref_resume_enabled_summary)
                setDefaultValue(false)
            }
            playbackCategory.addPreference(resumeEnabledPref)

            val resumeInfoPref = Preference(context).apply {
                key = "resume_info"
                title = getString(R.string.pref_resume_behavior_title)
                summary = getString(R.string.pref_resume_behavior_info)
                isSelectable = false
            }
            playbackCategory.addPreference(resumeInfoPref)

            val youtubeQualityModePref = ListPreference(context).apply {
    key = "youtube_quality_mode"
    title = getString(R.string.pref_youtube_quality_mode_title)
    summary = getString(R.string.pref_youtube_quality_mode_summary)
    entries = resources.getStringArray(R.array.youtube_quality_mode_entries)
    entryValues = resources.getStringArray(R.array.youtube_quality_mode_values)
    setDefaultValue("360_progressive")  // Safe default
    summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
}
playbackCategory.addPreference(youtubeQualityModePref)

val youtubeQualityInfoPref = Preference(context).apply {
    key = "youtube_quality_info"
    title = getString(R.string.pref_youtube_quality_info_title)
    summary = getString(R.string.pref_youtube_quality_info_summary)
    isSelectable = false
}
playbackCategory.addPreference(youtubeQualityInfoPref)

            // === AUDIO SECTION ===
            val audioCategory = PreferenceCategory(context).apply {
                key = "category_audio"
                title = getString(R.string.pref_category_audio)
            }
            screen.addPreference(audioCategory)

            val audioEnabledPref = SwitchPreference(context).apply {
                key = "audio_enabled"
                title = getString(R.string.pref_audio_enabled_title)
                summary = getString(R.string.pref_audio_enabled_summary)
                setDefaultValue(false)

                setOnPreferenceChangeListener { _, newValue ->
                    updateAudioVisibility(newValue as Boolean)
                    true
                }
            }
            audioCategory.addPreference(audioEnabledPref)

            val audioVolumePref = ListPreference(context).apply {
                key = "audio_volume"
                title = getString(R.string.pref_audio_volume_title)
                summary = getString(R.string.pref_audio_volume_summary)
                entries = resources.getStringArray(R.array.audio_volume_entries)
                entryValues = resources.getStringArray(R.array.audio_volume_values)
                setDefaultValue("50")
                isVisible = audioEnabledPref.isChecked
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }
            audioCategory.addPreference(audioVolumePref)

            // === CLOCK OVERLAY SECTION ===
val clockCategory = PreferenceCategory(context).apply {
                key = "category_clock"
                title = getString(R.string.pref_category_clock)
            }
            screen.addPreference(clockCategory)

            val clockEnabledPref = SwitchPreference(context).apply {
                key = "clock_enabled"
                title = getString(R.string.pref_clock_enabled_title)
                summary = getString(R.string.pref_clock_enabled_summary)
                setDefaultValue(false)

                setOnPreferenceChangeListener { _, newValue ->
                    updateClockVisibility(newValue as Boolean)
                    true
                }
            }
            clockCategory.addPreference(clockEnabledPref)

            val clockPositionPref = ListPreference(context).apply {
                key = "clock_position"
                title = getString(R.string.pref_clock_position_title)
                summary = getString(R.string.pref_clock_position_summary)
                entries = resources.getStringArray(R.array.position_entries)
                entryValues = resources.getStringArray(R.array.position_values)
                setDefaultValue("top_right")
                isVisible = clockEnabledPref.isChecked
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }
            clockCategory.addPreference(clockPositionPref)

            val clockSizePref = ListPreference(context).apply {
                key = "clock_size"
                title = getString(R.string.pref_clock_size_title)
                summary = getString(R.string.pref_clock_size_summary)
                entries = resources.getStringArray(R.array.clock_size_entries)
                entryValues = resources.getStringArray(R.array.clock_size_values)
                setDefaultValue("64")
                isVisible = clockEnabledPref.isChecked
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }
            clockCategory.addPreference(clockSizePref)

            val timeFormatPref = ListPreference(context).apply {
                key = "time_format"
                title = getString(R.string.pref_time_format_title)
                summary = getString(R.string.pref_time_format_summary)
                entries = resources.getStringArray(R.array.time_format_entries)
                entryValues = resources.getStringArray(R.array.time_format_values)
                setDefaultValue("12h")
                isVisible = clockEnabledPref.isChecked
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }
            clockCategory.addPreference(timeFormatPref)

            val pixelShiftPref = ListPreference(context).apply {
                key = "pixel_shift_interval"
                title = getString(R.string.pref_pixel_shift_title)
                summary = getString(R.string.pref_pixel_shift_summary)
                entries = resources.getStringArray(R.array.pixel_shift_entries)
                entryValues = resources.getStringArray(R.array.pixel_shift_values)
                setDefaultValue("300000")
                isVisible = clockEnabledPref.isChecked
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }
            clockCategory.addPreference(pixelShiftPref)

            // === VISUAL EFFECTS SECTION ===
            val effectsCategory = PreferenceCategory(context).apply {
                key = "category_effects"
                title = getString(R.string.pref_category_effects)
            }
            screen.addPreference(effectsCategory)

            val crossfadeEnabledPref = SwitchPreference(context).apply {
                key = "crossfade_enabled"
                title = getString(R.string.pref_crossfade_enabled_title)
                summary = getString(R.string.pref_crossfade_enabled_summary)
                setDefaultValue(false)

                setOnPreferenceChangeListener { _, newValue ->
                    updateCrossfadeVisibility(newValue as Boolean)
                    true
                }
            }
            effectsCategory.addPreference(crossfadeEnabledPref)

            val crossfadeDurationPref = ListPreference(context).apply {
                key = "crossfade_duration"
                title = getString(R.string.pref_crossfade_duration_title)
                summary = getString(R.string.pref_crossfade_duration_summary)
                entries = resources.getStringArray(R.array.crossfade_duration_entries)
                entryValues = resources.getStringArray(R.array.crossfade_duration_values)
                setDefaultValue("1000")
                isVisible = crossfadeEnabledPref.isChecked
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }
            effectsCategory.addPreference(crossfadeDurationPref)

            // === LOADING ANIMATION SECTION ===
            val loadingCategory = PreferenceCategory(context).apply {
                key = "category_loading"
                title = getString(R.string.pref_category_loading)
            }
            screen.addPreference(loadingCategory)

            val loadingAnimationPref = ListPreference(context).apply {
                key = "loading_animation_type"
                title = getString(R.string.pref_loading_animation_title)
                summary = getString(R.string.pref_loading_animation_summary)
                entries = resources.getStringArray(R.array.loading_animation_entries)
                entryValues = resources.getStringArray(R.array.loading_animation_values)
                setDefaultValue("spinning_dots")
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }
            loadingCategory.addPreference(loadingAnimationPref)

            val loadingTextPref = EditTextPreference(context).apply {
                key = "loading_animation_text"
                title = getString(R.string.pref_loading_text_title)
                summary = getString(R.string.pref_loading_text_summary)
                setDefaultValue("Loading")
                setOnBindEditTextListener { editText ->
                    editText.setSingleLine(true)
                }
            }
            loadingCategory.addPreference(loadingTextPref)

            // === DEBUG SECTION ===
            val debugCategory = PreferenceCategory(context).apply {
                key = "category_debug"
                title = getString(R.string.pref_category_debug)
            }
            screen.addPreference(debugCategory)

            val debugEnabledPref = SwitchPreference(context).apply {
                key = "debug_enabled"
                title = getString(R.string.pref_debug_enabled_title)
                summary = getString(R.string.pref_debug_enabled_summary)
                setDefaultValue(false)
            }
            debugCategory.addPreference(debugEnabledPref)

            val statsEnabledPref = SwitchPreference(context).apply {
                key = "stats_enabled"
                title = getString(R.string.pref_stats_enabled_title)
                summary = getString(R.string.pref_stats_enabled_summary)
                setDefaultValue(false)

                setOnPreferenceChangeListener { _, newValue ->
                    updateStatsVisibility(newValue as Boolean)
                    true
                }
            }
            debugCategory.addPreference(statsEnabledPref)

            val statsPositionPref = ListPreference(context).apply {
                key = "stats_position"
                title = getString(R.string.pref_stats_position_title)
                summary = getString(R.string.pref_stats_position_summary)
                entries = resources.getStringArray(R.array.position_entries)
                entryValues = resources.getStringArray(R.array.position_values)
                setDefaultValue("top_left")
                isVisible = statsEnabledPref.isChecked
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }
            debugCategory.addPreference(statsPositionPref)

            val statsIntervalPref = ListPreference(context).apply {
                key = "stats_interval"
                title = getString(R.string.pref_stats_interval_title)
                summary = getString(R.string.pref_stats_interval_summary)
                entries = resources.getStringArray(R.array.stats_interval_entries)
                entryValues = resources.getStringArray(R.array.stats_interval_values)
                setDefaultValue("1000")
                isVisible = statsEnabledPref.isChecked
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }
            debugCategory.addPreference(statsIntervalPref)

            preferenceScreen = screen
        }

        private fun updateScheduleVisibility(enabled: Boolean) {
            findPreference<SwitchPreference>("schedule_random_mode")?.isVisible = enabled
            findPreference<Preference>("clear_schedule_button")?.isVisible = enabled

            val days = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
            days.forEach { day ->
                findPreference<EditTextPreference>("url_$day")?.isVisible = enabled
                findPreference<SwitchPreference>("disable_url_$day")?.isVisible = enabled
                findPreference<Preference>("clear_${day}_button")?.isVisible = enabled
            }
        }

        private fun updateIntroVisibility(enabled: Boolean) {
            findPreference<EditTextPreference>("intro_duration")?.isVisible = enabled
        }

        private fun updateSkipBeginningVisibility(enabled: Boolean) {
            findPreference<EditTextPreference>("skip_beginning_duration")?.isVisible = enabled
        }

        private fun updateSpeedVisibility(enabled: Boolean) {
            findPreference<ListPreference>("playback_speed")?.isVisible = enabled
        }

        private fun updateAudioVisibility(enabled: Boolean) {
            findPreference<ListPreference>("audio_volume")?.isVisible = enabled
        }

        private fun updateClockVisibility(enabled: Boolean) {
            findPreference<ListPreference>("clock_position")?.isVisible = enabled
            findPreference<ListPreference>("clock_size")?.isVisible = enabled
            findPreference<ListPreference>("time_format")?.isVisible = enabled
            findPreference<ListPreference>("pixel_shift_interval")?.isVisible = enabled
        }

        private fun updateCrossfadeVisibility(enabled: Boolean) {
            findPreference<ListPreference>("crossfade_duration")?.isVisible = enabled
        }

        private fun updateStatsVisibility(enabled: Boolean) {
            findPreference<ListPreference>("stats_position")?.isVisible = enabled
            findPreference<ListPreference>("stats_interval")?.isVisible = enabled
        }

        private fun isRutubeUrl(url: String): Boolean {
            return url.contains("rutube.ru", ignoreCase = true)
        }

        private fun isYouTubeUrl(url: String): Boolean {
            return url.contains("youtube.com", ignoreCase = true) || 
                   url.contains("youtu.be", ignoreCase = true)
        }

        private fun autoExtractYouTube(youtubeUrl: String, preference: EditTextPreference) {
            showToast("Extracting YouTube stream...")

            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val result = youtubeExtractor.extractStream(youtubeUrl)

                    if (result.success && result.streamUrl != null) {
                        preference.text = result.streamUrl

                        val cachePrefs = requireContext().getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        cachePrefs.edit()
                            .putString(KEY_ORIGINAL_URL, youtubeUrl)
                            .putString(KEY_EXTRACTED_URL, result.streamUrl)
                            .putString(KEY_URL_TYPE, "youtube")
                            .apply()

                        showToast("✅ YouTube extracted (${result.quality ?: "Unknown"})")
                        Log.d(TAG, "YouTube URL extracted: ${result.streamUrl}")
                    } else {
                        showToast("❌ YouTube extraction failed: ${result.errorMessage ?: "Unknown error"}")
                        Log.e(TAG, "Failed to extract YouTube URL: ${result.errorMessage}")
                    }
                } catch (e: Exception) {
                    showToast("❌ YouTube extraction error")
                    Log.e(TAG, "YouTube extraction error", e)
                }
            }
        }

        private fun autoExtractRutube(rutubeUrl: String, preference: EditTextPreference) {
            showToast(getString(R.string.toast_rutube_extracting))

            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val m3u8Url = extractRutubeUrl(rutubeUrl)

                    if (m3u8Url != null) {
                        preference.text = m3u8Url

                        val cachePrefs = requireContext().getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        cachePrefs.edit()
                            .putString(KEY_ORIGINAL_URL, rutubeUrl)
                            .putString(KEY_EXTRACTED_URL, m3u8Url)
                            .putString(KEY_URL_TYPE, "rutube")
                            .apply()

                        showToast(getString(R.string.toast_rutube_success))
                        Log.d(TAG, "Rutube URL extracted: $m3u8Url")
                    } else {
                        showToast(getString(R.string.toast_rutube_failed))
                        Log.e(TAG, "Failed to extract Rutube URL")
                    }
                } catch (e: Exception) {
                    showToast(getString(R.string.toast_rutube_error))
                    Log.e(TAG, "Rutube extraction error", e)
                }
            }
        }

        private suspend fun extractRutubeUrl(rutubeUrl: String): String? = withContext(Dispatchers.IO) {
            try {
                val videoId = extractRutubeVideoId(rutubeUrl)
                if (videoId == null) {
                    Log.e(TAG, "Failed to extract Rutube video ID from: $rutubeUrl")
                    return@withContext null
                }

                val apiUrl = "https://rutube.ru/api/play/options/$videoId/?no_404=true&referer=https%3A%2F%2Frutube.ru"

                val request = Request.Builder()
                    .url(apiUrl)
                    .addHeader("Referer", "https://rutube.ru")
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                    .build()

                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.e(TAG, "Rutube API failed: ${response.code}")
                    return@withContext null
                }

                val json = response.body?.string()
                if (json.isNullOrEmpty()) {
                    Log.e(TAG, "Empty Rutube response")
                    return@withContext null
                }

                val jsonObject = JSONObject(json)
                var m3u8Url: String? = null

                val videoBalancer = jsonObject.optString("video_balancer")
                if (videoBalancer.isNotEmpty() && videoBalancer.startsWith("{")) {
                    try {
                        val balancerObj = JSONObject(videoBalancer)
                        m3u8Url = balancerObj.optString("m3u8")
                            .ifEmpty { balancerObj.optString("default") }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse video_balancer", e)
                    }
                } else if (videoBalancer.isNotEmpty()) {
                    m3u8Url = videoBalancer
                }

                if (m3u8Url.isNullOrEmpty()) {
                    val balancerObj = jsonObject.optJSONObject("video_balancer")
                    m3u8Url = balancerObj?.optString("m3u8")
                        ?: balancerObj?.optString("default")
                }

                if (m3u8Url.isNullOrEmpty()) {
                    m3u8Url = jsonObject.optString("m3u8")
                }

                if (m3u8Url.isNullOrEmpty()) {
                    m3u8Url = jsonObject.optString("hls")
                }

                if (m3u8Url.isNullOrEmpty()) {
                    Log.e(TAG, "No M3U8 URL found in Rutube response")
                } else {
                    Log.d(TAG, "✅ Rutube M3U8 extracted successfully")
                }

                m3u8Url
            } catch (e: Exception) {
                Log.e(TAG, "Rutube extraction error", e)
                null
            }
        }

        private fun extractRutubeVideoId(url: String): String? {
            val regex = "rutube\\.ru/video/([a-f0-9]+)".toRegex(RegexOption.IGNORE_CASE)
            return regex.find(url)?.groupValues?.get(1)
        }

        private fun showToast(message: String) {
            handler.post {
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            }
        }

        private fun String.capitalize() = this.replaceFirstChar { it.uppercase() }
    }
}