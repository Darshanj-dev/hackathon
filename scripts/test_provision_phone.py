import importlib.util
from pathlib import Path
import unittest
spec=importlib.util.spec_from_file_location('provision_phone',Path(__file__).with_name('provision-phone.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
class DeviceSelectionTests(unittest.TestCase):
 def test_only_authorized_phone_is_selected(self):self.assertEqual('phone',module.select_device('List of devices attached\nphone\tdevice\nother\tunauthorized\n'))
 def test_multiple_devices_require_explicit_selection(self):
  with self.assertRaises(RuntimeError):module.select_device('one\tdevice\ntwo\tdevice\n')
 def test_explicit_connected_device_is_used(self):self.assertEqual('two',module.select_device('one\tdevice\ntwo\tdevice\n','two'))
 def test_offline_phone_is_not_selected(self):
  with self.assertRaises(RuntimeError):module.select_device('phone\toffline\n')
if __name__=='__main__':unittest.main()
