package com.netveil.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.netveil.app.core.LogEntry
import com.netveil.app.ui.theme.VeilAmber
import com.netveil.app.ui.theme.VeilBorder
import com.netveil.app.ui.theme.VeilCyan
import com.netveil.app.ui.theme.VeilMuted
import com.netveil.app.ui.theme.VeilRed
import com.netveil.app.ui.theme.VeilSurface

@Composable
fun LogsScreen(
  coreVersion: String,
  logs: List<LogEntry>,
  onCopyLogs: () -> Unit,
  onClearLogs: () -> Unit
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 16.dp, vertical = 12.dp)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text("Журнал", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
          text = "$coreVersion · Секреты замаскированы",
          style = MaterialTheme.typography.bodySmall,
          color = VeilMuted
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
          onClick = onCopyLogs,
          border = BorderStroke(1.dp, VeilBorder),
          shape = RoundedCornerShape(10.dp)
        ) {
          Text("Копия")
        }
        OutlinedButton(
          onClick = onClearLogs,
          border = BorderStroke(1.dp, VeilBorder),
          shape = RoundedCornerShape(10.dp)
        ) {
          Text("Очистить")
        }
      }
    }

    Spacer(Modifier.height(12.dp))

    Card(
      colors = CardDefaults.cardColors(containerColor = VeilSurface),
      border = BorderStroke(1.dp, VeilBorder),
      shape = RoundedCornerShape(14.dp),
      modifier = Modifier.fillMaxSize()
    ) {
      if (logs.isEmpty()) {
        Column(
          modifier = Modifier.fillMaxSize(),
          verticalArrangement = Arrangement.Center,
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Text("Журнал пока пуст", color = VeilMuted)
        }
      } else {
        LazyColumn(
          modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          items(logs.asReversed()) { entry ->
            val levelColor = when (entry.level) {
              "ERR" -> VeilRed
              "WARN" -> VeilAmber
              else -> VeilCyan
            }
            Row {
              Text(
                text = "${entry.timestamp} [${entry.level}]",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = levelColor
              )
              Spacer(Modifier.width(8.dp))
              Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
              )
            }
          }
        }
      }
    }
  }
}
