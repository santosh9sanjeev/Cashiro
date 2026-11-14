package com.ritesh.cashiro.presentation.ui.features.settings.sms

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.ritesh.cashiro.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ritesh.cashiro.presentation.ui.features.categories.NavigationContent
import com.ritesh.cashiro.presentation.ui.components.CustomTitleTopAppBar
import com.ritesh.cashiro.presentation.ui.components.ListItem
import com.ritesh.cashiro.presentation.ui.components.ListItemPosition
import com.ritesh.cashiro.presentation.ui.components.toShape
import com.ritesh.cashiro.presentation.effects.overScrollVertical
import com.ritesh.cashiro.presentation.ui.features.settings.SettingsViewModel
import com.ritesh.cashiro.presentation.ui.icons.Clock
import com.ritesh.cashiro.presentation.ui.icons.Iconax
import com.ritesh.cashiro.presentation.ui.theme.Dimensions
import com.ritesh.cashiro.presentation.ui.theme.Spacing
import com.ritesh.cashiro.presentation.ui.theme.blue_dark
import com.ritesh.cashiro.presentation.ui.theme.blue_light
import com.ritesh.cashiro.presentation.ui.theme.cyan_dark
import com.ritesh.cashiro.presentation.ui.theme.cyan_light
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeEffectScope
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalHazeApi::class
)
@Composable
fun SMSScreen(
    onNavigateBack: () -> Unit,
    onNavigateToUnrecognizedSms: () -> Unit = {},
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    blurEffects: Boolean
) {
    val smsScanMonths by settingsViewModel.smsScanMonths.collectAsStateWithLifecycle(initialValue = 3)
    val smsScanAllTime by settingsViewModel.smsScanAllTime.collectAsStateWithLifecycle(initialValue = true)
    val smsScanCustomRange by settingsViewModel.smsScanCustomRange.collectAsStateWithLifecycle(initialValue = false)
    val smsScanFromDate by settingsViewModel.smsScanFromDate.collectAsStateWithLifecycle(initialValue = 0L)
    val smsScanToDate by settingsViewModel.smsScanToDate.collectAsStateWithLifecycle(initialValue = 0L)
    val unreportedCount by settingsViewModel.unreportedSmsCount.collectAsStateWithLifecycle()

    var showSmsScanDialog by remember { mutableStateOf(false) }
    var showDateRangePicker by remember { mutableStateOf(false) }

    val hasCustomRange = smsScanCustomRange && smsScanFromDate > 0L
    val scanPeriodSummary = when {
        hasCustomRange -> stringResource(
            R.string.scan_custom_range_format,
            formatScanDate(smsScanFromDate),
            formatScanDate(if (smsScanToDate > 0L) smsScanToDate else System.currentTimeMillis())
        )
        smsScanAllTime -> stringResource(R.string.scan_all_sms)
        else -> stringResource(R.string.scan_months_format, smsScanMonths)
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scrollBehaviorSmall = TopAppBarDefaults.pinnedScrollBehavior()
    val hazeState = remember { HazeState() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            CustomTitleTopAppBar(
                title = stringResource(R.string.sms_title),
                scrollBehaviorSmall = scrollBehaviorSmall,
                scrollBehaviorLarge = scrollBehavior,
                hazeState = hazeState,
                hasBackButton = true,
                navigationContent = { NavigationContent(onNavigateBack) }
            )
        }
    ) { paddingValues ->
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState)
                    .overScrollVertical()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = Dimensions.Padding.content,
                        end = Dimensions.Padding.content,
                        top = Dimensions.Padding.content +
                                paddingValues.calculateTopPadding()
                    ),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {


                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(1.5.dp)
                ) {
                    // SMS Scan Period
                    ListItem(
                        headline = {
                            Text(
                                text = stringResource(R.string.sms_scan_period),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                        },
                        supporting = {
                            Text(
                                text = scanPeriodSummary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        leading = {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(
                                        color = cyan_light,
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Iconax.Clock,
                                    contentDescription = null,
                                    tint = cyan_dark,
                                )
                            }
                        },
                        trailing = {
                            Box(
                                modifier = Modifier
                                    .background(
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = MaterialTheme.shapes.large
                                    )
                                    .clickable(
                                        onClick = { showSmsScanDialog = true }
                                    )
                                    .padding(
                                        horizontal = Spacing.md,
                                        vertical = Spacing.sm
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = when {
                                        hasCustomRange -> stringResource(R.string.range_custom_dates)
                                        smsScanAllTime -> stringResource(R.string.range_all_time)
                                        else -> stringResource(R.string.months_count_format, smsScanMonths)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        },
                        onClick = { showSmsScanDialog = true },
                        shape = ListItemPosition.Top.toShape(),
                        padding = PaddingValues(0.dp)
                    )

                    // Unrecognized Bank Messages
                    ListItem(
                        headline = {
                            Text(
                                text = stringResource(R.string.unrecognized_bank_messages),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                        },
                        supporting = {
                            Text(
                                text = if (unreportedCount > 0)
                                    pluralStringResource(R.plurals.unrecognized_messages_count, unreportedCount, unreportedCount)
                                else stringResource(R.string.no_unrecognized_messages),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        leading = {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(
                                        color = blue_light,
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.BugReport,
                                    contentDescription = null,
                                    tint = blue_dark
                                )
                            }
                        },
                        trailing = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (unreportedCount > 0) {
                                    Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                        Text(unreportedCount.toString())
                                    }
                                }

                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = { onNavigateToUnrecognizedSms() },
                        shape = ListItemPosition.Bottom.toShape(),
                        padding = PaddingValues(0.dp)
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.xl))
            }
        }

        // SMS Scan Period Dialog
        if (showSmsScanDialog) {
            val containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            AlertDialog(
                onDismissRequest = { showSmsScanDialog = false },
                title = { Text(stringResource(R.string.sms_scan_period)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(1.5.dp)) {
                        Text(
                            text = stringResource(R.string.sms_scan_period_desc),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(Spacing.md))

                        // -1 = all time, -2 = pick an explicit from/to window
                        val options = listOf(-1) + listOf(1, 2, 3, 6, 12, 24) + listOf(-2)
                        options.forEachIndexed { index, months ->
                            val isSelected = when (months) {
                                -2 -> hasCustomRange
                                -1 -> smsScanAllTime && !hasCustomRange
                                else -> smsScanMonths == months && !smsScanAllTime && !hasCustomRange
                            }

                            ListItem(
                                headline = {
                                    Text(
                                        text = when (months) {
                                            -2 -> stringResource(R.string.range_custom_dates)
                                            -1 -> stringResource(R.string.range_all_time)
                                            1 -> stringResource(R.string.one_month)
                                            24 -> stringResource(R.string.two_years)
                                            else -> stringResource(R.string.months_count_format, months)
                                        }
                                    )
                                },
                                trailing = {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = null
                                    )
                                },
                                selected = isSelected,
                                onClick = {
                                    when (months) {
                                        -2 -> {
                                            showSmsScanDialog = false
                                            showDateRangePicker = true
                                        }
                                        -1 -> {
                                            settingsViewModel.updateSmsScanAllTime(true)
                                            showSmsScanDialog = false
                                        }
                                        else -> {
                                            settingsViewModel.updateSmsScanMonths(months)
                                            settingsViewModel.updateSmsScanAllTime(false)
                                            showSmsScanDialog = false
                                        }
                                    }
                                },
                                shape = ListItemPosition.from(index, options.size).toShape(),
                                listColor = MaterialTheme.colorScheme.surface.copy(0.5f),
                                padding = PaddingValues(0.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showSmsScanDialog = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(0.5f),
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier
                            .padding(horizontal = Spacing.xl)
                            .fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.cancel),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                },
                containerColor = if (blurEffects)
                    MaterialTheme.colorScheme.surfaceContainerLow.copy(0.5f)
                else MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .then(
                        if (blurEffects) Modifier.hazeEffect(
                            state = hazeState,
                            block = fun HazeEffectScope.() {
                                style = HazeDefaults.style(
                                    backgroundColor = Color.Transparent,
                                    tint = HazeDefaults.tint(containerColor),
                                    blurRadius = 20.dp,
                                    noiseFactor = -1f,
                                )
                                blurredEdgeTreatment = BlurredEdgeTreatment.Unbounded
                            }
                        ) else Modifier
                    ),
                shape = RoundedCornerShape(16.dp),
            )
        }

        // Custom from/to scan window
        if (showDateRangePicker) {
            val rangeState = rememberDateRangePickerState(
                initialSelectedStartDateMillis = smsScanFromDate.takeIf { it > 0L },
                initialSelectedEndDateMillis = smsScanToDate.takeIf { it > 0L }
            )
            DatePickerDialog(
                onDismissRequest = { showDateRangePicker = false },
                confirmButton = {
                    TextButton(
                        enabled = rangeState.selectedStartDateMillis != null,
                        onClick = {
                            val start = rangeState.selectedStartDateMillis
                            if (start != null) {
                                // No end date selected means "from this date until now".
                                val end = rangeState.selectedEndDateMillis ?: start
                                settingsViewModel.updateSmsScanDateRange(
                                    pickerDateToLocalMillis(start, endOfDay = false),
                                    pickerDateToLocalMillis(end, endOfDay = true)
                                )
                            }
                            showDateRangePicker = false
                        }
                    ) {
                        Text(stringResource(R.string.save_changes))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDateRangePicker = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            ) {
                DateRangePicker(
                    state = rangeState,
                    showModeToggle = true,
                    title = {
                        Text(
                            text = stringResource(R.string.scan_custom_range_title),
                            modifier = Modifier.padding(start = Spacing.xl, top = Spacing.md)
                        )
                    }
                )
            }
        }
    }
}

/** Formats a stored scan-window bound for display. */
private fun formatScanDate(millis: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(millis))

/**
 * The Material date picker reports UTC midnight for the day the user tapped.
 * Rebuild that calendar day in the device's own timezone so the window lines up
 * with the local timestamps stored against each message.
 */
private fun pickerDateToLocalMillis(utcMillis: Long, endOfDay: Boolean): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
    return Calendar.getInstance().apply {
        set(Calendar.YEAR, utc.get(Calendar.YEAR))
        set(Calendar.MONTH, utc.get(Calendar.MONTH))
        set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH))
        if (endOfDay) {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        } else {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }.timeInMillis
}
