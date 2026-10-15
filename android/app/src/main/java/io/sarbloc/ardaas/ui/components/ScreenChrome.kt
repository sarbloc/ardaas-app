package io.sarbloc.ardaas.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.sarbloc.ardaas.R
import io.sarbloc.ardaas.ui.ArdaasColors

/** Corner radius of a raised card — iOS's inset-grouped list rows. */
val CardShape = RoundedCornerShape(12.dp)

/**
 * Top bar colours over the brand gradient: transparent at rest so the
 * gradient runs behind the status bar, navy once content scrolls under it.
 * Toolbar icons take the kesri accent, as iOS tints its toolbar buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ardaasTopAppBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = Color.Transparent,
    scrolledContainerColor = ArdaasColors.NavyTop,
    titleContentColor = ArdaasColors.Parchment,
    navigationIconContentColor = ArdaasColors.Kesri,
    actionIconContentColor = ArdaasColors.Kesri,
)

/** A pushed screen's top bar: title plus an Up arrow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopAppBar(
    title: String,
    onBack: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.navigate_back))
            }
        },
        colors = ardaasTopAppBarColors(),
        scrollBehavior = scrollBehavior,
    )
}

/**
 * A whole-screen message — the counterpart of iOS's `ContentUnavailableView`:
 * an icon, a title, a description and an optional action, centred.
 */
@Composable
fun ContentUnavailable(
    icon: Painter,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = ArdaasColors.Mist, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = ArdaasColors.Parchment,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = ArdaasColors.Mist,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/**
 * One section of a grouped list — iOS's `Section` in an inset-grouped
 * `List`: an optional header over a raised card. Separate rows with
 * [SectionDivider].
 */
@Composable
fun GroupedSection(
    header: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (header != null) {
            Text(
                header,
                style = MaterialTheme.typography.titleSmall,
                color = ArdaasColors.Mist,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp).semantics { heading() },
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().clip(CardShape).background(ArdaasColors.RaisedFill),
            content = content,
        )
    }
}

/** Hairline between rows of a [GroupedSection], inset like iOS's row separators. */
@Composable
fun SectionDivider() {
    HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}
