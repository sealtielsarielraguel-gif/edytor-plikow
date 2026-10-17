package pl.edytor.plikow

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.*
import java.nio.ByteBuffer
import java.util.zip.*

enum class Tryb { TEKST, ZIP, HEX }

// Tabela formatów do wyboru przy zapisie
val FORMATY = listOf(
    ".html" to "Strona internetowa (HTML)", ".css" to "Arkusz stylów (CSS)",
    ".js" to "Skrypt JavaScript", ".py" to "Skrypt Python", ".json" to "Dane JSON",
    ".md" to "Dokument Markdown", ".txt" to "Zwykły tekst", ".xml" to "Dane XML",
    ".csv" to "Tabela CSV", ".zip" to "Archiwum ZIP", ".gguf" to "Model GGUF (binarny)"
)
val TEKSTOWE = setOf("html", "htm", "css", "js", "json", "md", "txt", "xml", "py", "csv", "svg",
    "yaml", "yml", "ts", "kt", "java", "c", "cpp", "sh", "ini", "log", "sql")

// Odczyt archiwum ZIP do pamięci (nazwa wpisu -> bajty)
fun czytajZip(s: InputStream): Map<String, ByteArray> {
    val m = LinkedHashMap<String, ByteArray>()
    ZipInputStream(s).use { z ->
        var e = z.nextEntry
        while (e != null) { if (!e.isDirectory) m[e.name] = z.readBytes(); e = z.nextEntry }
    }
    return m
}

// Zapis wpisów do archiwum ZIP
fun spakuj(m: Map<String, ByteArray>): ByteArray {
    val o = ByteArrayOutputStream()
    ZipOutputStream(o).use { z -> m.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    return o.toByteArray()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Edytor() }
            }
        }
    }
}

@Composable
fun DialogNazwy(tytul: String, start: String, ok: (String) -> Unit, anuluj: () -> Unit) {
    var t by remember { mutableStateOf(start) }
    AlertDialog(
        onDismissRequest = anuluj, title = { Text(tytul) },
        text = { OutlinedTextField(t, { t = it }, singleLine = true) },
        confirmButton = { TextButton({ if (t.isNotBlank()) ok(t.trim()) }) { Text("OK") } },
        dismissButton = { TextButton(anuluj) { Text("Anuluj") } }
    )
}

@Composable
fun Edytor() {
    val ctx = LocalContext.current
    val cr = ctx.contentResolver
    var uri by remember { mutableStateOf<Uri?>(null) }
    var nazwa by remember { mutableStateOf("nowy.html") }
    var tryb by remember { mutableStateOf(Tryb.TEKST) }
    var tekst by remember { mutableStateOf("") }
    var zmieniono by remember { mutableStateOf(false) }
    var wpisy by remember { mutableStateOf<Map<String, ByteArray>>(emptyMap()) }
    var wpis by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableStateOf(0L) }
    var rozmiar by remember { mutableStateOf(0L) }
    var hex by remember { mutableStateOf("") }
    var liczba by remember { mutableStateOf(0) }
    var okno by remember { mutableStateOf("") }

    fun msg(t: String) = Toast.makeText(ctx, t, Toast.LENGTH_LONG).show()

    fun nazwaZ(u: Uri): String =
        cr.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "plik"

    // Wczytuje 256 bajtów od podanej pozycji (bez ładowania całego pliku)
    fun czytajHex(u: Uri, o: Long) {
        cr.openFileDescriptor(u, "r")!!.use { p ->
            val k = FileInputStream(p.fileDescriptor).channel
            rozmiar = k.size()
            val b = ByteBuffer.allocate(256)
            k.position(o)
            val n = maxOf(k.read(b), 0)
            hex = (0 until n).joinToString(" ") { "%02X".format(b.get(it)) }
            liczba = n
            offset = o
        }
    }

    fun idz(o: Long) { try { czytajHex(uri!!, o) } catch (e: Throwable) { msg("Błąd odczytu: ${e.message}") } }

    fun wczytaj(u: Uri, t: Tryb) {
        try {
            when (t) {
                Tryb.ZIP -> wpisy = czytajZip(cr.openInputStream(u)!!)
                Tryb.TEKST -> tekst = cr.openInputStream(u)!!.use { String(it.readBytes()) }
                Tryb.HEX -> czytajHex(u, 0)
            }
            tryb = t; wpis = null; zmieniono = false
        } catch (e: Throwable) { msg("Nie udało się wczytać pliku: ${e.message}") }
    }

    fun otworz(u: Uri) {
        try {
            cr.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) {}
        uri = u
        nazwa = nazwaZ(u)
        val e = nazwa.substringAfterLast('.', "").lowercase()
        wczytaj(u, if (e == "zip") Tryb.ZIP else if (e in TEKSTOWE) Tryb.TEKST else Tryb.HEX)
    }

    // Zatwierdza edytowany wpis archiwum ZIP
    fun zastosuj() {
        wpis?.let { wpisy = wpisy + (it to tekst.toByteArray()); wpis = null }
    }

    fun zapiszDo(u: Uri, n: String) {
        try {
            if (tryb == Tryb.HEX) {
                val b = hex.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
                if (b.size != liczba) { msg("Liczba bajtów musi pozostać taka sama ($liczba)."); return }
                cr.openFileDescriptor(u, "rw")!!.use { p ->
                    FileOutputStream(p.fileDescriptor).channel.apply { position(offset); write(ByteBuffer.wrap(b)) }
                }
            } else {
                val dane = when {
                    tryb == Tryb.ZIP -> spakuj(wpisy)
                    n.endsWith(".zip", true) -> spakuj(mapOf(nazwa to tekst.toByteArray()))
                    else -> tekst.toByteArray()
                }
                cr.openOutputStream(u, "wt")!!.use { it.write(dane) }
            }
            zmieniono = false
            msg("Zapisano: $n")
        } catch (e: Throwable) { msg("Nie udało się zapisać: ${e.message}") }
    }

    val otwieracz = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) otworz(u) }
    val tworca = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { u ->
        if (u != null) { val n = nazwaZ(u); zapiszDo(u, n); uri = u; nazwa = n }
    }

    fun zapisz() { zastosuj(); val u = uri; if (u == null) tworca.launch(nazwa) else zapiszDo(u, nazwa) }

    val stylKodu = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Text(nazwa + if (zmieniono) " •" else "", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton({ uri = null; nazwa = "nowy.html"; tekst = ""; tryb = Tryb.TEKST; wpis = null; zmieniono = false }) { Text("Nowy") }
            Button({ otwieracz.launch(arrayOf("*/*")) }) { Text("Otwórz") }
            Button({ zapisz() }) { Text("Zapisz") }
            OutlinedButton({
                zastosuj()
                if (tryb == Tryb.HEX) msg("W trybie hex możliwy jest tylko zapis w miejscu (nadpisanie).") else okno = "format"
            }) { Text("Zapisz jako…") }
            OutlinedButton({ okno = "nazwa" }) { Text("Zmień nazwę") }
            uri?.let { u ->
                OutlinedButton({ wczytaj(u, Tryb.TEKST) }) { Text("Jako tekst") }
                OutlinedButton({ wczytaj(u, Tryb.HEX) }) { Text("Jako hex") }
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            tryb == Tryb.HEX -> Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Offset: $offset (0x${offset.toString(16).uppercase()}) · rozmiar: $rozmiar B")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton({ idz(maxOf(offset - 256, 0)) }) { Text("◀ Wstecz") }
                    OutlinedButton({ if (offset + 256 < rozmiar) idz(offset + 256) }) { Text("Dalej ▶") }
                }
                OutlinedTextField(hex, { hex = it; zmieniono = true }, Modifier.fillMaxWidth(), textStyle = stylKodu)
                Text("Edytuj bajty szesnastkowo (liczba bajtów musi się zgadzać) i naciśnij Zapisz. Zmiany trafiają prosto do pliku, bez wczytywania całości.")
            }
            tryb == Tryb.ZIP && wpis == null -> Column {
                Button({ okno = "wpis" }) { Text("Dodaj plik do archiwum") }
                LazyColumn {
                    items(wpisy.keys.toList()) { k ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(k, Modifier.weight(1f))
                            if (k.substringAfterLast('.', "").lowercase() in TEKSTOWE)
                                TextButton({ wpis = k; tekst = String(wpisy[k]!!) }) { Text("Edytuj") }
                            TextButton({ wpisy = wpisy - k; zmieniono = true }) { Text("Usuń") }
                        }
                    }
                }
            }
            else -> Column(Modifier.fillMaxWidth().weight(1f)) {
                wpis?.let { Text("Wpis w archiwum: $it (Zapisz zatwierdza zmiany)") }
                OutlinedTextField(
                    tekst, { tekst = it; zmieniono = true }, Modifier.fillMaxSize(), textStyle = stylKodu,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrect = false)
                )
            }
        }
    }

    if (okno == "format") AlertDialog(
        onDismissRequest = { okno = "" }, confirmButton = {},
        title = { Text("Wybierz format zapisu") },
        text = {
            LazyColumn {
                item {
                    Row(Modifier.fillMaxWidth()) {
                        Text("Format", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        Text("Opis", Modifier.weight(2f), fontWeight = FontWeight.Bold)
                    }
                }
                items(FORMATY) { (e, opis) ->
                    Row(Modifier.fillMaxWidth().clickable { okno = ""; tworca.launch(nazwa.substringBeforeLast('.') + e) }.padding(vertical = 12.dp)) {
                        Text(e, Modifier.weight(1f), fontFamily = FontFamily.Monospace)
                        Text(opis, Modifier.weight(2f))
                    }
                }
            }
        }
    )
    if (okno == "nazwa") DialogNazwy("Zmień nazwę pliku", nazwa, { n ->
        okno = ""
        val u = uri
        if (u == null) nazwa = n else try {
            val nowy = DocumentsContract.renameDocument(cr, u, n)
            if (nowy == null) msg("Ten dostawca plików nie pozwala zmienić nazwy.") else { uri = nowy; nazwa = nazwaZ(nowy); msg("Zmieniono nazwę.") }
        } catch (e: Throwable) { msg("Nie udało się zmienić nazwy: ${e.message}") }
    }, { okno = "" })
    if (okno == "wpis") DialogNazwy("Nazwa nowego pliku w archiwum", "nowy.txt", { n ->
        okno = ""; wpisy = wpisy + (n to ByteArray(0)); wpis = n; tekst = ""; zmieniono = true
    }, { okno = "" })
}
