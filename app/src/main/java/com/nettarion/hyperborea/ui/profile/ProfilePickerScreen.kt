package com.nettarion.hyperborea.ui.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nettarion.hyperborea.core.model.Profile
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors

@Composable
fun ProfilePickerScreen(
    onProfileSelected: () -> Unit,
    onCreateProfile: () -> Unit,
    onGuest: () -> Unit = onProfileSelected,
    viewModel: ProfilePickerViewModel = hiltViewModel(),
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val colors = LocalHyperboreaColors.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Who's riding?",
                style = MaterialTheme.typography.headlineLarge,
                color = colors.textHigh,
            )
            Spacer(Modifier.height(48.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                contentPadding = PaddingValues(horizontal = 48.dp),
            ) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileCard(
                        profile = profile,
                        onClick = { viewModel.selectProfile(profile.id, onProfileSelected) },
                    )
                }
                item(key = "add") {
                    AddProfileCard(onClick = onCreateProfile)
                }
            }
            Spacer(Modifier.height(48.dp))
            Text(
                text = "Guest",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textLow,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(onClick = onGuest)
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ProfileCard(
    profile: Profile,
    onClick: () -> Unit,
) {
    val colors = LocalHyperboreaColors.current
    val initials = profile.name.take(2).uppercase()
    val avatarColors = listOf(
        colors.electricBlue,
        colors.statusActive,
        colors.accentWarm,
        colors.statusError,
    )
    val avatarColor = avatarColors[(profile.id % avatarColors.size).toInt()]

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
            .width(100.dp),
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(avatarColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initials,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textHigh,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = profile.name,
            style = MaterialTheme.typography.titleMedium,
            color = colors.textHigh,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AddProfileCard(onClick: () -> Unit) {
    val colors = LocalHyperboreaColors.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
            .width(100.dp),
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = "New Profile",
                tint = colors.textMedium,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "New Profile",
            style = MaterialTheme.typography.titleMedium,
            color = colors.textMedium,
            textAlign = TextAlign.Center,
        )
    }
}
