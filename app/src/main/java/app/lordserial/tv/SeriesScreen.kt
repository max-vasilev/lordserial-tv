package app.lordserial.tv

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay

internal val BACKGROUND = Color(0xFF090B12)
internal val SURFACE = Color(0xFF1C1F2B)
internal val MUTED = Color(0xFFB8BBC7)
internal val ACCENT = Color(0xFFB52A36)

private const val TITLE = "ОЧЕНЬ СТРАННЫЕ ДЕЛА"
private const val FACTS = "2016–2025 · фантастика, ужасы, драма"
// Shown until the wiki on Fandom has been read once.
private const val DESCRIPTION = "Хокинс, 1983 год. Пропадает мальчик, и его друзья, семья и шеф полиции " +
    "раскрывают тайну секретной лаборатории, девочки со сверхспособностями и Изнанки."
private val SIDE = 56.dp
// The header's text column: about half the screen, as streaming apps keep the synopsis, so lines
// stay short enough to read from the sofa and the still shows on the right.
private val TEXT_WIDTH = 520.dp
private val CARD_SHAPE = RoundedCornerShape(10.dp)

/** Watched to the end credits, give or take. */
private fun isWatched(episode: Episode, position: Int?) =
    position != null && episode.duration > 0 && position >= episode.duration - 180

private fun plural(n: Int, one: String, few: String, many: String): String {
    val word = when {
        n % 100 in 11..14 -> many
        n % 10 == 1 -> one
        n % 10 in 2..4 -> few
        else -> many
    }
    return "$n $word"
}

private fun minutes(seconds: Int) = "${(seconds + 59) / 60} мин"

/**
 * The series page, laid out like the TV apps of streaming services: the focused episode's still
 * fills the background, the title, a short description and a Continue button sit at the top left,
 * and the seasons and a row of episode cards run along the bottom. Focusing a season tab switches
 * the row to it. While focus is on the tabs or the cards, the focused episode takes the
 * description's place, so the header never stacks the series and an episode at once.
 */
@Composable
fun SeriesScreen(
    seasons: List<Season>,
    last: Pair<Int, Int>?,
    positions: Map<String, Int>,
    stills: Stills,
    info: SeriesInfo,
    onOpen: (Episode) -> Unit
) {
    val all = remember(seasons) { seasons.flatMap { it.episodes } }
    val lastEpisode = last?.let { (s, e) -> all.firstOrNull { it.season == s && it.number == e } }
    val lastWatched = lastEpisode != null && isWatched(lastEpisode, positions[lastEpisode.key])
    val resume = when {
        lastEpisode == null -> all.first()
        lastWatched -> all.getOrNull(all.indexOf(lastEpisode) + 1) ?: lastEpisode
        else -> lastEpisode
    }
    val resumeLabel = when {
        lastEpisode == null -> "Смотреть с первой серии"
        lastWatched && resume != lastEpisode -> "Смотреть следующую серию"
        lastWatched -> "Смотреть снова"
        else -> "Продолжить просмотр"
    }
    val resumePosition = positions[resume.key]
    val resumeNote = "${resume.season} сезон, ${resume.number} серия" +
        if (!isWatched(resume, resumePosition) && resumePosition != null && resumePosition > 60 &&
            resume.duration > 0
        ) " · осталось ${minutes(resume.duration - resumePosition)}" else ""

    // The playlist is replaced when a fresh copy arrives; with the same seasons and episode counts
    // that must not reset where the viewer is on the screen.
    val shape = seasons.map { it.number to it.episodes.size }
    var selectedSeason by remember(shape) { mutableIntStateOf(resume.season) }
    val season = seasons.firstOrNull { it.number == selectedSeason } ?: seasons.first()
    var hero by remember(shape) { mutableStateOf(resume) }
    // Focus on the Continue button, rather than on the tabs or the cards.
    var onTop by remember { mutableStateOf(true) }
    // The background follows focus only once it rests, so skimming the row does not load a still
    // for every card passed. The lines over the button change at once.
    var backdrop by remember(shape) { mutableStateOf(resume) }
    LaunchedEffect(hero) {
        delay(300)
        backdrop = hero
    }

    // The card a season's row starts on: the episode to resume, else the first one not watched.
    fun entryIndex(of: Season) =
        of.episodes.indexOf(resume).takeIf { it >= 0 } ?: of.episodes.indexOfFirst {
            !isWatched(it, positions[it.key])
        }.coerceAtLeast(0)

    val resumeFocus = remember { FocusRequester() }
    val tabFocus = remember(shape) { seasons.associate { it.number to FocusRequester() } }
    val cardFocus = remember(shape, season.number) { season.episodes.map { FocusRequester() } }
    var rowIndex by remember(shape, season.number) { mutableIntStateOf(entryIndex(season)) }
    val rowState = rememberLazyListState()
    LaunchedEffect(Unit) { runCatching { resumeFocus.requestFocus() } }
    LaunchedEffect(shape, season.number) { rowState.scrollToItem(rowIndex) }
    // Episodes the build has no still for get one made ahead, the open season first, one at a time
    // once the visible cards have started theirs. Only while this screen is open, so it never
    // competes with playback for the connection. Built-in stills return at once.
    LaunchedEffect(season) {
        delay(1_500)
        for (episode in season.episodes + all.filter { it.season != season.number }) stills.load(episode)
    }

    Box(modifier = Modifier.fillMaxSize().background(BACKGROUND)) {
        EpisodeImage(backdrop, stills, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to BACKGROUND,
                    0.3f to BACKGROUND.copy(alpha = 0.85f),
                    0.6f to BACKGROUND.copy(alpha = 0.35f),
                    1f to BACKGROUND.copy(alpha = 0.15f)
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.4f to Color.Transparent, 1f to BACKGROUND)
            )
        )

        Column(modifier = Modifier.fillMaxSize().padding(top = 28.dp, bottom = 22.dp)) {
            Column(modifier = Modifier.padding(horizontal = SIDE)) {
                Text(TITLE, fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(4.dp))
                // One block of fixed height, so the row below never moves: the series while focus is
                // on the button, the focused episode once it is on the tabs or the cards.
                Crossfade(
                    targetState = if (onTop) null else hero,
                    modifier = Modifier.width(TEXT_WIDTH).height(126.dp),
                    label = "header"
                ) { episode ->
                    if (episode == null) {
                        Column {
                            Text(
                                "$FACTS · ${plural(seasons.size, "сезон", "сезона", "сезонов")} · " +
                                    plural(all.size, "серия", "серии", "серий"),
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                maxLines = 1,
                                color = MUTED
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                info.description ?: DESCRIPTION,
                                fontSize = 16.sp,
                                lineHeight = 24.sp,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    } else {
                        val about = info.episodes[episode.key]
                        Column {
                            Text(
                                (listOf("${episode.season} сезон", "${episode.number} серия") +
                                    episodeDetails(episode, positions[episode.key]))
                                    .filter { it.isNotEmpty() }.joinToString(" · "),
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                color = MUTED
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                about?.title ?: "${episode.number} серия",
                                fontSize = 24.sp,
                                lineHeight = 30.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = Color.White
                            )
                            about?.synopsis?.let {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    it,
                                    fontSize = 15.sp,
                                    lineHeight = 22.sp,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    color = Color.White.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onOpen(resume) },
                    modifier = Modifier
                        .focusRequester(resumeFocus)
                        .onFocusChanged {
                            if (it.isFocused) {
                                hero = resume
                                onTop = true
                            }
                        },
                    colors = ButtonDefaults.colors(
                        containerColor = SURFACE,
                        contentColor = Color.White,
                        focusedContainerColor = Color.White,
                        focusedContentColor = BACKGROUND
                    )
                ) {
                    Column {
                        Text("▶  $resumeLabel", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(
                            resumeNote,
                            modifier = Modifier.padding(start = 26.dp),
                            fontSize = 13.sp,
                            color = LocalContentColor.current.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Row(
                // Focus enters the tabs on the current season. Landing on whichever tab is nearest
                // would switch the season on the way down.
                modifier = Modifier
                    .padding(horizontal = SIDE - 12.dp)
                    .focusProperties { onEnter = { tabFocus.getValue(season.number).requestFocus() } }
                    .focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (tab in seasons) {
                    SeasonTab(
                        number = tab.number,
                        selected = tab.number == season.number,
                        modifier = Modifier
                            .focusRequester(tabFocus.getValue(tab.number))
                            .onFocusChanged {
                                if (it.isFocused) {
                                    selectedSeason = tab.number
                                    hero = tab.episodes[entryIndex(tab)]
                                    onTop = false
                                }
                            },
                        onClick = { selectedSeason = tab.number }
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            LazyRow(
                state = rowState,
                // Focus enters the row on the card it left, or on the episode to resume.
                modifier = Modifier
                    .focusProperties { onEnter = { cardFocus[rowIndex].requestFocus() } }
                    .focusGroup(),
                contentPadding = PaddingValues(horizontal = SIDE),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                items(season.episodes, key = { it.key }) { episode ->
                    EpisodeCard(
                        episode = episode,
                        position = positions[episode.key],
                        stills = stills,
                        title = info.episodes[episode.key]?.title,
                        modifier = Modifier
                            .focusRequester(cardFocus[season.episodes.indexOf(episode)])
                            .onFocusChanged {
                                if (it.isFocused) {
                                    hero = episode
                                    rowIndex = season.episodes.indexOf(episode)
                                    onTop = false
                                }
                            },
                        onClick = { onOpen(episode) }
                    )
                }
            }
        }
    }
}

private fun episodeDetails(episode: Episode, position: Int?): String {
    val parts = mutableListOf<String>()
    if (episode.duration > 0) parts += minutes(episode.duration)
    when {
        isWatched(episode, position) -> parts += "просмотрено"
        position != null && position > 60 && episode.duration > 0 ->
            parts += "осталось ${minutes(episode.duration - position)}"
    }
    return parts.joinToString(" · ")
}

@Composable
private fun SeasonTab(number: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (selected) Color.White else MUTED,
            focusedContainerColor = Color.White,
            focusedContentColor = BACKGROUND
        )
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "$number сезон",
                fontSize = 16.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
            Box(
                Modifier
                    .padding(top = 3.dp)
                    .width(22.dp)
                    .height(2.dp)
                    .background(if (selected) ACCENT else Color.Transparent)
            )
        }
    }
}

@Composable
private fun EpisodeCard(
    episode: Episode,
    position: Int?,
    stills: Stills,
    title: String?,
    modifier: Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val watched = isWatched(episode, position)
    Column(modifier = Modifier.width(224.dp)) {
        Card(
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .onFocusChanged { focused = it.isFocused },
            shape = CardDefaults.shape(CARD_SHAPE),
            colors = CardDefaults.colors(containerColor = SURFACE),
            scale = CardDefaults.scale(focusedScale = 1.06f),
            border = CardDefaults.border(
                focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = CARD_SHAPE)
            )
        ) {
            Box(Modifier.fillMaxSize()) {
                // Only for an episode with no still yet; the still covers it once made.
                if (remember(episode) { stills.cached(episode) == null }) {
                    Text(
                        "${episode.number}",
                        modifier = Modifier.align(Alignment.Center),
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        color = MUTED.copy(alpha = 0.5f)
                    )
                }
                EpisodeImage(episode, stills, Modifier.fillMaxSize())
                if (!watched && position != null && position > 60 && episode.duration > 0) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color.White.copy(alpha = 0.3f))
                    )
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth((position.toFloat() / episode.duration).coerceIn(0f, 1f))
                            .height(4.dp)
                            .background(ACCENT)
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (title != null) "${episode.number}. $title" else "${episode.number} серия",
            fontSize = 15.sp,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (focused) Color.White else MUTED
        )
        if (episode.duration > 0) {
            Text(minutes(episode.duration), fontSize = 13.sp, color = MUTED.copy(alpha = 0.7f))
        }
    }
}

/** The episode's still; one made from the stream fades in once ready, never a blurry stand-in. */
@Composable
private fun EpisodeImage(episode: Episode, stills: Stills, modifier: Modifier) {
    // Keyed state, so a new episode never shows the previous one's still while its own loads.
    val still = remember(episode) { mutableStateOf(stills.cached(episode)) }
    // Only a still that arrives later fades in; one that is there already just shows.
    val late = remember(episode) { still.value == null }
    LaunchedEffect(episode) {
        if (still.value == null) still.value = stills.load(episode)
    }
    val image = still.value ?: return
    val context = LocalContext.current
    AsyncImage(
        model = remember(image) { ImageRequest.Builder(context).data(image).crossfade(late).build() },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
    )
}
