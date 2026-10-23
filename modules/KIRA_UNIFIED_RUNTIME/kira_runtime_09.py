"""Kira 0.9: unified process facade for UUI and the persistent TRIAD event store.

This is a tested orchestration layer, not a semantic AI or an atomic transaction
across the two existing SQLite databases.
"""
from pathlib import Path
import json
from uui_bridge import UUIBridge
from uui_lang import Runtime
from kira_experience_08 import ExperienceStore

class KiraRuntime:
    def __init__(self, home):
        self.home = Path(home)
        self.home.mkdir(parents=True, exist_ok=True)
        self.experience = ExperienceStore(self.home / 'experience.sqlite')
        try:
            self.bridge = UUIBridge(kira_db=str(self.home / 'knowledge.sqlite'))
        except BaseException:
            self.experience.close()
            raise

    def execute(self, source):
        """Run real UUI code using the existing bridge and VM.
        UUI operations on knowledge DB and TRIAD journal are separate commits.
        """
        return Runtime(self.bridge).run(source)

    def event(self, event_id, kind, source, payload):
        return self.experience.append(event_id, kind, source, payload)

    def state(self):
        return self.experience.triad.snapshot()

    def history(self, limit=100):
        return self.experience.events(limit=limit)

    def verify(self):
        return self.experience.verify()

    def close(self):
        try:
            self.bridge.close()
        finally:
            self.experience.close()

    def __enter__(self): return self
    def __exit__(self, *args): self.close()
