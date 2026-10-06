package com.rhnxdev.hzplayer.browser.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.browser.AdBlockStatus
import com.rhnxdev.hzplayer.browser.BrowserCacheMode
import com.rhnxdev.hzplayer.browser.BrowserSettings
import com.rhnxdev.hzplayer.browser.UserAgentMode
import com.rhnxdev.hzplayer.core.components.HzPlayerTopBar
import com.rhnxdev.hzplayer.core.designsystem.Spacing

import androidx.compose.material3.CircularProgressIndicator
import com.rhnxdev.hzplayer.browser.adblock.AdBlockEngine
import com.rhnxdev.hzplayer.browser.adblock.AdBlockListManager

/**
 * Solid full-screen settings window for the browser.
 * Uses [HzPlayerTopBar] and solid surface background to match SettingsScreen.
 */
@Composable
fun BrowserSettingsScreen(
    visible: Boolean,
    settings: BrowserSettings,
    onSave: (BrowserSettings) -> Unit,
    onDismiss: () -> Unit,
    isAdBlockUpdating: Boolean = false,
    adBlockStatus: AdBlockStatus? = null,
    onUpdateAdBlockFilters: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Toolbar with back navigation
        HzPlayerTopBar(
            title = stringResource(R.string.browser_settings_title),
            showBack = true,
            onBack = onDismiss,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            // ── JavaScript ──────────────────────────────────────
            item {
                SettingsSectionHeader(
                    title = stringResource(R.string.browser_js_section),
                    icon = Icons.Default.Code,
                )
            }

            item {
                BrowserSettingsToggleCard(
                    title = stringResource(R.string.browser_enable_js),
                    subtitle = stringResource(R.string.browser_enable_js_sub),
                    checked = settings.javaScriptEnabled,
                    onCheckedChange = { onSave(settings.copy(javaScriptEnabled = it)) },
                )
            }
            item {
                AnimatedVisibility(
                    visible = settings.javaScriptEnabled,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    BrowserSettingsToggleCard(
                        title = stringResource(R.string.browser_allow_popups),
                        subtitle = stringResource(R.string.browser_allow_popups_sub),
                        checked = settings.javaScriptCanOpenWindows,
                        onCheckedChange = { onSave(settings.copy(javaScriptCanOpenWindows = it)) },
                    )
                }
            }

            // ── Ad Blocker Engine ──────────────────────
            item {
                SettingsSectionHeader(
                    title = stringResource(R.string.browser_adblock_section),
                    icon = Icons.Default.Block,
                )
            }

            if (!AdBlockEngine.isAvailable) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(Spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = stringResource(R.string.browser_adblock_unavailable),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            } else {
                item {
                    BrowserSettingsToggleCard(
                        title = stringResource(R.string.browser_enable_adblock),
                        subtitle = stringResource(R.string.browser_enable_adblock_sub),
                        checked = settings.adBlockEnabled,
                        onCheckedChange = { onSave(settings.copy(adBlockEnabled = it)) },
                        icon = Icons.Default.Block,
                    )
                }
            }
            item {
                AnimatedVisibility(
                    visible = settings.adBlockEnabled,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        // Dynamic Cosmetic Element Hiding toggle
                        BrowserSettingsToggleCard(
                            title = stringResource(R.string.browser_cosmetic_hiding),
                            subtitle = stringResource(R.string.browser_cosmetic_hiding_sub),
                            checked = settings.cosmeticFilteringEnabled,
                            onCheckedChange = { onSave(settings.copy(cosmeticFilteringEnabled = it)) },
                        )

                        // Block Cross Domain Popups toggle
                        BrowserSettingsToggleCard(
                            title = stringResource(R.string.browser_block_cross_domain_popups),
                            subtitle = stringResource(R.string.browser_block_cross_domain_popups_sub),
                            checked = settings.blockCrossDomainPopups,
                            onCheckedChange = { onSave(settings.copy(blockCrossDomainPopups = it)) },
                        )

                        // Status Card & Update Button
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(Spacing.md),
                                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.browser_filter_lists_status),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = stringResource(
                                                R.string.browser_active_rules,
                                                AdBlockEngine.totalRuleCount,
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        val statusText = when {
                                            isAdBlockUpdating ->
                                                stringResource(R.string.browser_adblock_updating)
                                            adBlockStatus is AdBlockStatus.Updated ->
                                                stringResource(
                                                    R.string.browser_adblock_updated,
                                                    adBlockStatus.ruleCount,
                                                )
                                            adBlockStatus is AdBlockStatus.Failed ->
                                                stringResource(R.string.browser_adblock_update_failed)
                                            else -> null
                                        }
                                        if (statusText != null) {
                                            Text(
                                                text = statusText,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }

                                    TextButton(
                                        onClick = onUpdateAdBlockFilters,
                                        enabled = !isAdBlockUpdating
                                    ) {
                                        if (isAdBlockUpdating) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp
                                            )
                                            Spacer(modifier = Modifier.width(Spacing.xs))
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.Refresh,
                                                contentDescription = stringResource(R.string.browser_update_filter_lists),
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(Spacing.xs))
                                        }
                                        Text(stringResource(R.string.browser_update_now))
                                    }
                                }
                            }
                        }

                        // Filter Subscriptions Toggles
                        Text(
                            text = stringResource(R.string.browser_filter_subscriptions),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = Spacing.xs)
                        )

                        AdBlockListManager.BUILTIN_LISTS.forEach { list ->
                            val isEnabled = settings.enabledFilterLists.contains(list.id)
                            BrowserSettingsToggleCard(
                                title = stringResource(list.nameRes),
                                subtitle = stringResource(list.descriptionRes),
                                checked = isEnabled,
                                onCheckedChange = { checked ->
                                    val newLists = if (checked) {
                                        settings.enabledFilterLists + list.id
                                    } else {
                                        settings.enabledFilterLists - list.id
                                    }
                                    onSave(settings.copy(enabledFilterLists = newLists))
                                }
                            )
                        }

                        // Custom Rules Input
                        Text(
                            text = stringResource(R.string.browser_custom_filter_rules),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = Spacing.xs)
                        )

                        OutlinedTextField(
                            value = settings.customAdBlockRules,
                            onValueChange = { onSave(settings.copy(customAdBlockRules = it)) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.browser_custom_filter_rules_hint)) },
                            maxLines = 5,
                            singleLine = false,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline
                            )
                        )
                    }
                }
            }

            // ── Privacy & Security ──────────────────────────────
            item {
                SettingsSectionHeader(
                    title = stringResource(R.string.browser_privacy_section),
                    icon = Icons.Default.Security,
                )
            }

            item {
                BrowserSettingsToggleCard(
                    title = stringResource(R.string.browser_enable_cookies),
                    subtitle = stringResource(R.string.browser_enable_cookies_sub),
                    checked = settings.cookiesEnabled,
                    onCheckedChange = { onSave(settings.copy(cookiesEnabled = it)) },
                    icon = Icons.Default.Cookie,
                )
            }
            item {
                AnimatedVisibility(
                    visible = settings.cookiesEnabled,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    BrowserSettingsToggleCard(
                        title = stringResource(R.string.browser_third_party_cookies),
                        subtitle = stringResource(R.string.browser_third_party_cookies_sub),
                        checked = settings.thirdPartyCookiesEnabled,
                        onCheckedChange = { onSave(settings.copy(thirdPartyCookiesEnabled = it)) },
                    )
                }
            }
            item {
                BrowserSettingsToggleCard(
                    title = stringResource(R.string.browser_block_mixed_content),
                    subtitle = stringResource(R.string.browser_block_mixed_content_sub),
                    checked = settings.blockMixedContent,
                    onCheckedChange = { onSave(settings.copy(blockMixedContent = it)) },
                    icon = Icons.Default.Lock,
                )
            }
            item {
                BrowserSettingsToggleCard(
                    title = stringResource(R.string.browser_safe_browsing),
                    subtitle = stringResource(R.string.browser_safe_browsing_sub),
                    checked = settings.safeBrowsingEnabled,
                    onCheckedChange = { onSave(settings.copy(safeBrowsingEnabled = it)) },
                    icon = Icons.Default.Security,
                )
            }
            item {
                BrowserSettingsToggleCard(
                    title = stringResource(R.string.browser_allow_app_links),
                    subtitle = stringResource(R.string.browser_allow_app_links_sub),
                    checked = settings.allowIntentLinks,
                    onCheckedChange = { onSave(settings.copy(allowIntentLinks = it)) },
                    icon = Icons.Default.OpenInNew,
                )
            }

            // ── User Agent ──────────────────────────────────────
            item {
                SettingsSectionHeader(
                    title = stringResource(R.string.browser_ua_section),
                    icon = Icons.Default.PhoneAndroid,
                )
            }

            item {
                BrowserSettingsSelectorCard(
                    title = stringResource(R.string.browser_ua_mode),
                    subtitle = stringResource(R.string.browser_ua_mode_sub),
                    options = UserAgentMode.entries,
                    selectedOption = settings.userAgentMode,
                    optionLabel = { stringResource(it.labelRes) },
                    onSelect = { onSave(settings.copy(userAgentMode = it)) },
                    icon = Icons.Default.PhoneAndroid,
                    extraContent = {
                        AnimatedVisibility(
                            visible = settings.userAgentMode == UserAgentMode.CUSTOM,
                            enter = expandVertically(),
                            exit = shrinkVertically(),
                        ) {
                            var draftUa by remember(settings.customUserAgent) {
                                mutableStateOf(settings.customUserAgent)
                            }
                            OutlinedTextField(
                                value = draftUa,
                                onValueChange = {
                                    draftUa = it
                                    onSave(settings.copy(customUserAgent = it))
                                },
                                label = { Text(stringResource(R.string.browser_custom_ua)) },
                                placeholder = {
                                    Text(
                                        stringResource(R.string.browser_custom_ua_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = Spacing.sm),
                                singleLine = false,
                                maxLines = 3,
                                textStyle = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                ),
                            )
                        }
                    }
                )
            }

            // ── Content ─────────────────────────────────────────
            item {
                SettingsSectionHeader(
                    title = stringResource(R.string.browser_content_section),
                    icon = Icons.Default.TextFields,
                )
            }

            item {
                BrowserSettingsToggleCard(
                    title = stringResource(R.string.browser_dom_storage),
                    subtitle = stringResource(R.string.browser_dom_storage_sub),
                    checked = settings.domStorageEnabled,
                    onCheckedChange = { onSave(settings.copy(domStorageEnabled = it)) },
                    icon = Icons.Default.Storage,
                )
            }
            item {
                BrowserSettingsToggleCard(
                    title = stringResource(R.string.browser_load_images),
                    subtitle = stringResource(R.string.browser_load_images_sub),
                    checked = settings.loadImagesAutomatically,
                    onCheckedChange = { onSave(settings.copy(loadImagesAutomatically = it)) },
                    icon = Icons.Default.Image,
                )
            }
            item {
                BrowserSettingsToggleCard(
                    title = stringResource(R.string.browser_dark_web_content),
                    subtitle = stringResource(R.string.browser_dark_web_content_sub),
                    checked = settings.darkWebContent,
                    onCheckedChange = { onSave(settings.copy(darkWebContent = it)) },
                    icon = Icons.Default.DarkMode,
                )
            }
            item {
                BrowserSettingsToggleCard(
                    title = stringResource(R.string.browser_autoplay_media),
                    subtitle = stringResource(R.string.browser_autoplay_media_sub),
                    checked = !settings.mediaPlaybackRequiresGesture,
                    onCheckedChange = { onSave(settings.copy(mediaPlaybackRequiresGesture = !it)) },
                    icon = Icons.Default.PlayCircleOutline,
                )
            }
            item {
                BrowserSettingsSliderCard(
                    title = stringResource(R.string.browser_text_zoom),
                    subtitle = stringResource(R.string.browser_text_zoom_sub),
                    formattedValue = stringResource(R.string.browser_text_zoom_value, settings.textZoom),
                    value = settings.textZoom.toFloat(),
                    onValueChange = { onSave(settings.copy(textZoom = it.toInt())) },
                    valueRange = 50f..200f,
                    steps = 14,
                    icon = Icons.Default.ZoomIn,
                )
            }

            // ── Cache ───────────────────────────────────────────
            item {
                SettingsSectionHeader(
                    title = stringResource(R.string.browser_cache_section),
                    icon = Icons.Default.Storage,
                )
            }

            item {
                BrowserSettingsSelectorCard(
                    title = stringResource(R.string.browser_cache_mode),
                    subtitle = stringResource(R.string.browser_cache_mode_sub),
                    options = BrowserCacheMode.entries,
                    selectedOption = settings.cacheMode,
                    optionLabel = { stringResource(it.labelRes) },
                    onSelect = { onSave(settings.copy(cacheMode = it)) },
                    icon = Icons.Default.Storage,
                )
            }

            // ── Tabs & Session ──────────────────────────────────
            item {
                SettingsSectionHeader(
                    title = stringResource(R.string.browser_tabs_session_section),
                    icon = Icons.Default.Tab,
                )
            }

            item {
                BrowserSettingsToggleCard(
                    title = stringResource(R.string.browser_restore_tabs),
                    subtitle = stringResource(R.string.browser_restore_tabs_sub),
                    checked = settings.restoreTabsOnStartup,
                    onCheckedChange = { onSave(settings.copy(restoreTabsOnStartup = it)) },
                    icon = Icons.Default.Tab,
                )
            }

            item { Spacer(Modifier.height(Spacing.xxl)) }
        }
    }
}

// ── Reusable internal components matching app/player settings design ──

@Composable
private fun SettingsSectionHeader(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg)
            .padding(top = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun BrowserSettingsToggleCard(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Card(
        onClick = { if (enabled) onCheckedChange(!checked) },
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg),
        shape = RoundedCornerShape(Spacing.sm),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        val alpha = if (enabled) 1f else 0.38f
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.md, horizontal = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(Spacing.md))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                )
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    )
                }
            }
            Spacer(Modifier.width(Spacing.sm))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
    }
}

@Composable
private fun BrowserSettingsSliderCard(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    formattedValue: String? = null,
    subtitle: String? = null,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg),
        shape = RoundedCornerShape(Spacing.sm),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.md, horizontal = Spacing.md),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(Spacing.md))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (subtitle != null) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (formattedValue != null) {
                    Text(
                        text = formattedValue,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(modifier = Modifier.height(Spacing.xs))
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> BrowserSettingsSelectorCard(
    title: String,
    subtitle: String,
    options: List<T>,
    selectedOption: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    extraContent: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg),
        shape = RoundedCornerShape(Spacing.sm),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.md, horizontal = Spacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(Spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                modifier = Modifier.fillMaxWidth(),
            ) {
                options.forEach { option ->
                    val isSelected = option == selectedOption
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelect(option) },
                        label = {
                            Text(
                                text = optionLabel(option),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }
            extraContent?.invoke()
        }
    }
}
