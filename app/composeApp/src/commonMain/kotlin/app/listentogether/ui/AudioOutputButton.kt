package app.listentogether.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.listentogether.AppController

@Composable
expect fun AudioOutputButton(controller: AppController, modifier: Modifier = Modifier)
