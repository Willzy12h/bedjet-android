package io.github.willzy12h.nocturne.data;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import io.github.willzy12h.nocturne.core.store.*;
import io.github.willzy12h.nocturne.core.protocol.StoredProgram;
public class ImportOriginalLabelTest {
  @Test public void emptySlotAndUnnamedProgrammeMustRemainDistinguishableInTheReview() {
    String at = "2026-10-24T22:30Z", device = "ble:AA:BB:CC:DD:EE:FF";
    SlotBackup empty = new SlotBackup(device,0,null,at,true,false,false);
    SlotBackup unnamed = new SlotBackup(device,1,new StoredProgram("",List.of()),at,false,false,false);
    BackupBundle data = new BackupBundle(2,at,List.of(),new Settings(),List.of(empty,unnamed),List.of());
    var rows = ImportPreviewKt.importSections(data,data,data).get(3).getEntries();
    assertNotEquals("An unnamed programme must never be reviewed as an empty slot",rows.get(0).getName(),rows.get(1).getName());
  }
}
