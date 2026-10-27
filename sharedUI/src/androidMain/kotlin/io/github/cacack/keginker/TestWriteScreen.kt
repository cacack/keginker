package io.github.cacack.keginker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.cacack.keginker.ezsign.TestQuestion
import io.github.cacack.keginker.waveshare.writeProgressMessage

/** What test writes do, shown with the offer on the display report and again before the user confirms. */
const val TEST_WRITES_OFFER = "Writes a few test patterns to the display and asks what you see. This replaces what the display shows."

/** The display report's offer of test writes; [onTestWrites] opens the confirmation, which writes nothing yet. */
@Composable
fun TestWritesOffer(onTestWrites: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = onTestWrites) { Text("Test writes") }
        Text(TEST_WRITES_OFFER, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Report a display's guided test writes, one screen per [state] of a
 * [TestWriteController]: the confirmation, waiting for the display, the
 * write, its questions, and a failed write. [TestWriteState.Done] isn't
 * shown here; the report shows the records instead. [onBack] is offered
 * except while a write runs.
 */
@Composable
fun TestWriteScreen(
    state: TestWriteState,
    familyName: String,
    onOptIn: () -> Unit,
    onAnswer: (List<String>) -> Unit,
    onRetry: () -> Unit,
    onFinish: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state !is TestWriteState.Writing) TextButton(onClick = onBack) { Text("Back") }
        Text("Test writes", style = MaterialTheme.typography.headlineSmall)
        when (state) {
            TestWriteState.Offered -> {
                Text(TEST_WRITES_OFFER)
                Text("Only the $familyName display you reported is written. Nothing is written until you start.")
                Button(onClick = onOptIn) { Text("Start test writes") }
            }
            is TestWriteState.Waiting -> {
                Text("Pattern ${state.index + 1} of ${state.count}: ${state.pattern.title}", style = MaterialTheme.typography.titleMedium)
                Text("Hold the display to the phone and keep it still. If it's already there, lift it away and back.")
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            is TestWriteState.Writing -> {
                Text(state.pattern.title, style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
                Text(writeProgressMessage(state.percent, phone = "phone"))
            }
            is TestWriteState.Asking -> Questions(state, onAnswer)
            is TestWriteState.Failed -> {
                Text(state.record.pattern, style = MaterialTheme.typography.titleMedium)
                Text(state.message, color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRetry) { Text("Retry") }
                    OutlinedButton(onClick = onFinish) { Text("Finish and see report") }
                }
            }
            is TestWriteState.Done -> Unit
        }
    }
}

/** [state]'s questions as radio groups; Next sends the answers once every question has one. */
@Composable
private fun Questions(state: TestWriteState.Asking, onAnswer: (List<String>) -> Unit) {
    val choices = remember(state) { mutableStateListOf<String?>().apply { repeat(state.questions.size) { add(null) } } }
    Text(state.pattern.title, style = MaterialTheme.typography.titleMedium)
    Text("The pattern is on the display. Look at it and answer.")
    state.questions.forEachIndexed { i, question ->
        Question(question, choices[i]) { choices[i] = it }
    }
    Button(enabled = choices.none { it == null }, onClick = { onAnswer(choices.filterNotNull()) }) { Text("Next") }
}

@Composable
private fun Question(question: TestQuestion, chosen: String?, onChoose: (String) -> Unit) {
    Column(Modifier.selectableGroup()) {
        Text(question.text)
        question.options.forEach { option ->
            Row(
                modifier = Modifier.fillMaxWidth()
                    .selectable(selected = option == chosen, role = Role.RadioButton, onClick = { onChoose(option) })
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option == chosen, onClick = null)
                Text(option, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
