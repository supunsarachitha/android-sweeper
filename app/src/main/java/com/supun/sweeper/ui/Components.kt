package com.supun.sweeper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.supun.sweeper.R
import com.supun.sweeper.util.formatBytes

/** The shared building blocks of the screens: top bar, section headers, tool cards, bottom bars, empty states. */

/** Replaces the space between a number and its unit, so "121 MB" never breaks across two lines. */
internal fun nbBytes(bytes: Long): String = formatBytes(bytes).replace(' ', '\u00A0')

/** The container colour used by every card, so the screens look the same. */
@Composable
internal fun sweeperCardColors(): CardColors =
    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

/** Top bar for every screen. Pass [onBack] on all screens except Home. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SweeperTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

/** Small coloured heading that groups the cards below it. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp),
    )
}

/** A round tonal background with an icon in it. */
@Composable
fun TonalIcon(icon: Int, modifier: Modifier = Modifier, size: Int = 40) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size((size / 2 + 2).dp),
        )
    }
}

/**
 * A tappable card for one tool: tonal icon on the left, title and a short line, an optional [badge] (a short
 * value such as a size) and a chevron on the right. [highlight] is an extra line in the accent colour.
 */
@Composable
fun ToolCard(
    icon: Int,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    highlight: String? = null,
) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth(), colors = sweeperCardColors()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TonalIcon(icon)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (highlight != null) {
                    Text(
                        highlight,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (badge != null) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The bar at the bottom of a review screen: what is selected, and the one button that acts on it. */
@Composable
fun BottomActionBar(
    summary: String,
    buttonText: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summaryModifier: Modifier = Modifier,
    buttonModifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Row(
            modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                summary,
                style = MaterialTheme.typography.titleMedium,
                modifier = summaryModifier.weight(1f),
            )
            Button(onClick = onClick, enabled = enabled, modifier = buttonModifier) { Text(buttonText) }
        }
    }
}

/** An icon, a title and an optional line, centred: shown instead of a list that has nothing in it. */
@Composable
fun EmptyState(icon: Int, title: String, modifier: Modifier = Modifier, body: String? = null) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(40.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A compact tonal card for hints and notices. With [onDismiss] it gets a close button. */
@Composable
fun InfoCard(
    modifier: Modifier = Modifier,
    icon: Int? = R.drawable.ic_info,
    onDismiss: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = if (onDismiss == null) 16.dp else 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (icon != null) {
                Icon(
                    painterResource(icon),
                    contentDescription = null,
                    modifier = Modifier.padding(top = 2.dp).size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f), content = content)
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = "Dismiss", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
