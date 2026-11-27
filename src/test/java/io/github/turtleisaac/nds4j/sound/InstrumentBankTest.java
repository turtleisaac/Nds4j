/*
 * Copyright (c) 2023 Turtleisaac.
 *
 * This file is part of Nds4j.
 *
 * Nds4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Nds4j is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Nds4j. If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.turtleisaac.nds4j.sound;

import io.github.turtleisaac.nds4j.framework.MemBuf;
import io.github.turtleisaac.nds4j.sound.InstrumentBank.NoteRegion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SBNK programs built in memory: a single region, a drum set, a key split, and an unknown type.
 */
@DisplayName("SBNK instrument regions")
class InstrumentBankTest
{
    @Test
    @DisplayName("each instrument type resolves the note it claims")
    void resolvesRegions()
    {
        byte[] file = new byte[0x120];
        System.arraycopy("SBNK".getBytes(StandardCharsets.US_ASCII), 0, file, 0, 4);
        {
            MemBuf scratch = MemBuf.create(file);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(4);
            scratch.writer().writeShort((short) (0xFEFF));
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, file, 0, file.length);
        }
        {
            MemBuf scratch = MemBuf.create(file);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(6);
            scratch.writer().writeShort((short) (0x0100));
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, file, 0, file.length);
        }
        {
            MemBuf scratch = MemBuf.create(file);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(12);
            scratch.writer().writeShort((short) (0x10));
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, file, 0, file.length);
        }
        {
            MemBuf scratch = MemBuf.create(file);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(14);
            scratch.writer().writeShort((short) (1));
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, file, 0, file.length);
        }
        System.arraycopy("DATA".getBytes(StandardCharsets.US_ASCII), 0, file, 0x10, 4);
        {
            MemBuf scratch = MemBuf.create(file);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(0x38);
            scratch.writer().writeUInt32(5);
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, file, 0, file.length);
        }

        int table = 0x3C;
        int pcm = 0x50;
        int drum = 0x60;
        int split = 0x90;
        body(file, pcm, 7, 2, 60, 1, 2, 3, 4, 64);
        // drum: notes 36 and 37, each a 12-byte record after the two range bytes
        file[drum] = 36;
        file[drum + 1] = 37;
        {
            MemBuf scratch = MemBuf.create(file);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(drum + 2);
            scratch.writer().writeShort((short) (1));
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, file, 0, file.length);
        }
        body(file, drum + 4, 1, 0, 36, 0, 0, 0, 0, 32);
        {
            MemBuf scratch = MemBuf.create(file);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(drum + 14);
            scratch.writer().writeShort((short) (2));
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, file, 0, file.length);
        }
        body(file, drum + 16, 5, 0, 37, 0, 0, 0, 0, 40);
        // key split: bounds 48 and 72, then two regions
        file[split] = 48;
        file[split + 1] = 72;
        {
            MemBuf scratch = MemBuf.create(file);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(split + 8);
            scratch.writer().writeShort((short) (1));
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, file, 0, file.length);
        }
        body(file, split + 10, 9, 1, 48, 8, 7, 6, 5, 10);
        {
            MemBuf scratch = MemBuf.create(file);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(split + 20);
            scratch.writer().writeShort((short) (3));
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, file, 0, file.length);
        }
        body(file, split + 22, 0, 0, 72, 1, 1, 1, 1, 20);

        entry(file, table, 0, 0);
        entry(file, table + 4, 1, pcm);
        entry(file, table + 8, 16, drum);
        entry(file, table + 12, 17, split);
        entry(file, table + 16, 18, pcm);

        InstrumentBank bank = InstrumentBank.fromBytes(file);
        assertThat(bank.save()).isEqualTo(file);
        assertThat(bank.getInstrumentCount()).isEqualTo(5);
        assertThat(bank.getInstrument(0).isEmpty()).isTrue();
        assertThat(bank.resolve(0, 60)).isNull();
        assertThat(bank.resolve(-1, 0)).isNull();
        assertThat(bank.resolve(9, 0)).isNull();

        NoteRegion played = bank.resolve(1, 60);
        assertThat(played.waveIndex).isEqualTo(7);
        assertThat(played.waveArcIndex).isEqualTo(2);
        assertThat(played.baseNote).isEqualTo(60);
        assertThat(played.attack).isEqualTo(1);
        assertThat(played.decay).isEqualTo(2);
        assertThat(played.sustain).isEqualTo(3);
        assertThat(played.release).isEqualTo(4);
        assertThat(played.pan).isEqualTo(64);
        assertThat(played.isPcm).isTrue();
        assertThat(played.getDuty()).isEqualTo(7);

        assertThat(bank.resolve(2, 36).waveIndex).isEqualTo(1);
        assertThat(bank.resolve(2, 37).isPsg).isTrue();
        assertThat(bank.resolve(2, 37).getDuty()).isEqualTo(5);
        assertThat(bank.resolve(2, 35)).isNull();
        assertThat(bank.resolve(2, 80)).isNull();

        assertThat(bank.resolve(3, 40).waveIndex).isEqualTo(9);
        assertThat(bank.resolve(3, 60).isNoise).isTrue();
        assertThat(bank.resolve(3, 90)).isNull();
        assertThat(bank.getInstrument(4).type).isEqualTo(18);
        assertThat(bank.getInstrument(4).isEmpty()).isTrue();
        assertThat(bank.resolve(4, 60)).isNull();
    }

    private static void entry(byte[] file, int at, int type, int offset)
    {
        file[at] = (byte) type;
        {
            MemBuf scratch = MemBuf.create(file);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(at + 1);
            scratch.writer().writeShort((short) (offset));
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, file, 0, file.length);
        }
    }

    private static void body(byte[] file, int at, int wave, int arc, int note, int attack, int decay, int sustain,
            int release, int pan)
    {
        {
            MemBuf scratch = MemBuf.create(file);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(at);
            scratch.writer().writeShort((short) (wave));
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, file, 0, file.length);
        }
        {
            MemBuf scratch = MemBuf.create(file);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(at + 2);
            scratch.writer().writeShort((short) (arc));
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, file, 0, file.length);
        }
        file[at + 4] = (byte) note;
        file[at + 5] = (byte) attack;
        file[at + 6] = (byte) decay;
        file[at + 7] = (byte) sustain;
        file[at + 8] = (byte) release;
        file[at + 9] = (byte) pan;
    }
}
