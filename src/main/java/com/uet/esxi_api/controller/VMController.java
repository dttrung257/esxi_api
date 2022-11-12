package com.uet.esxi_api.controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.validation.Valid;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.repository.query.Param;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.github.tuupertunut.powershelllibjava.PowerShell;
import com.github.tuupertunut.powershelllibjava.PowerShellExecutionException;
import com.uet.esxi_api.dto.vm.NewVM;
import com.uet.esxi_api.dto.vm.UpdateCpuRam;
import com.uet.esxi_api.entity.OSName;
import com.uet.esxi_api.entity.VM;
import com.uet.esxi_api.entity.VMState;
import com.uet.esxi_api.entity.WebUser;
import com.uet.esxi_api.exception.vm.CannotSuspendVMException;
import com.uet.esxi_api.exception.vm.CannotUpdateStorageException;
import com.uet.esxi_api.exception.vm.CannotUpdateVMException;
import com.uet.esxi_api.exception.vm.InsufficientConfigurationParametersException;
import com.uet.esxi_api.exception.vm.InvalidOSNameException;
import com.uet.esxi_api.exception.vm.NotFoundVMException;
import com.uet.esxi_api.exception.vm.VMAlreadyInStateException;
import com.uet.esxi_api.service.VMService;

@RestController
@RequestMapping("/api")
@Validated
public class VMController {
	@Value("${esxi_server.ip}")
	private String serverIp;
	@Value("${esxi_server.username}")
	private String serverUsername;
	@Value("${esxi_server.password}")
	private String serverPassword;
	@Autowired
	private VMService vmService;

	@PostMapping("/VM/create_VM")
	public ResponseEntity<Object> createVM(@Valid @RequestBody NewVM newVM) {
		final String name = newVM.getName();
		final String os = newVM.getOs();
		final Integer numCPU = newVM.getNumCPU();
		final Integer ramGB = newVM.getRamGB();
		final Integer storage = newVM.getStorage();
		
		if (!(os.equalsIgnoreCase(OSName.OS_UBUNTU) || os.equalsIgnoreCase(OSName.OS_WINDOW))) {
			throw new InvalidOSNameException("OS name " + os.toUpperCase() + " is invalid");
		}
		if (os.equalsIgnoreCase(OSName.OS_UBUNTU)) {
			if (numCPU < 1 || ramGB < 1 || storage < 4) {
				throw new InsufficientConfigurationParametersException("Configuration parameters are not enough");
			}
		}
		if (os.equalsIgnoreCase(OSName.OS_WINDOW)) {
			if (numCPU < 1 || ramGB < 2 || storage < 32) {
				throw new InsufficientConfigurationParametersException("Configuration parameters are not enough");
			}
		}
		WebUser user = (WebUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
		VM vm = new VM();
		vm.setName(name);
		vm.setOs(os.toUpperCase());
		vm.setNumCPU(numCPU);
		vm.setRamGB(ramGB);
		vm.setStorage(storage);
		vm.setUser(user);

		String createVMCmd = String.format(
				"PowerShell -File \"src/main/resources/create_VM.ps1\" %s %s %s %s %s %d %d %d", serverIp,
				serverUsername, serverPassword, name, os.toUpperCase(), numCPU, ramGB, storage);
		try (PowerShell psSession = PowerShell.open()) {
			String ip = psSession.executeCommands(createVMCmd);
			vm.setState(VMState.STATE_POWERED_ON);
			vm.setIp(ip.replace("\n", ""));
			return ResponseEntity.status(HttpStatus.CREATED).body(vmService.createVM(vm));
		} catch (IOException | PowerShellExecutionException ex) {
			ex.printStackTrace();
		}
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Fail to create VM " + newVM.getName());
	}

	@GetMapping("/VM/delete_VM/{name}")
	public ResponseEntity<Object> deleteVM(@PathVariable("name") @NotBlank String name) {
		VM vm = vmService.findByName(name);
		if (vm == null) {
			throw new NotFoundVMException("Not found VM with name: " + name);
		}
		String deleteVMCmd = String.format("PowerShell -File \"src/main/resources/delete_VM.ps1\" %s %s %s %s",
				serverIp, serverUsername, serverPassword, name);
		try (PowerShell psSession = PowerShell.open()) {
			String response = psSession.executeCommands(deleteVMCmd);
			vmService.deleteVM(vm);
			return ResponseEntity.ok(response);
		} catch (IOException | PowerShellExecutionException ex) {
			ex.printStackTrace();
		}
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Fail to delete VM " + name);
	}

	@GetMapping("/VM/get_info_VM")
	public ResponseEntity<Object> getInfoVM() {
		WebUser user = (WebUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
		List<VM> vms = new ArrayList<>();
		vmService.getListVMName(user.getId()).forEach(vm -> {
			if (vm.getState().equals(VMState.STATE_SUSPENDED)) {
				vm.setIp(null);
				vms.add(vm);
			} else {
				vms.add(vm);
			}
		});
		return ResponseEntity.ok(vms);
	}

	@GetMapping("/VM/start_VM/{name}")
	public ResponseEntity<Object> startVM(@PathVariable("name") @NotBlank String name) {
		VM vm = vmService.findByName(name);
		if (vm == null) {
			throw new NotFoundVMException("Not found VM with name: " + name);
		}
		if (vm.getState().equals(VMState.STATE_POWERED_ON)) {
			throw new VMAlreadyInStateException("VM " + name + " already in power on state");
		}
		String startVMCmd = String.format("PowerShell -File \"src/main/resources/start_VM.ps1\" %s %s %s %s", serverIp,
				serverUsername, serverPassword, name);
		try (PowerShell psSession = PowerShell.open()) {
			psSession.executeCommands(startVMCmd);
			if (vm.getState().equals(VMState.STATE_POWERED_OFF)) {
				String getIpVMCmd = String.format("PowerShell -File \"src/main/resources/get_ip_VM.ps1\" %s %s %s %s",
						serverIp, serverUsername, serverPassword, name);
				String ip = psSession.executeCommands(getIpVMCmd).replace("\n", "");
				vm.setIp(ip);
				vm.setState(VMState.STATE_POWERED_ON);
				return ResponseEntity.ok(vmService.save(vm));
			}
			vm.setState(VMState.STATE_POWERED_ON);
			return ResponseEntity.ok(vmService.save(vm));
		} catch (IOException | PowerShellExecutionException ex) {
			ex.printStackTrace();
		}
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Fail to start VM " + name);
	}

	@GetMapping("/VM/suspend_VM/{name}")
	public ResponseEntity<Object> suspendVM(@PathVariable("name") @NotBlank String name) {
		VM vm = vmService.findByName(name);
		if (vm == null) {
			throw new NotFoundVMException("Not found VM with name: " + name);
		}
		if (vm.getState().equals(VMState.STATE_SUSPENDED)) {
			throw new VMAlreadyInStateException("VM " + name + " already in suspended state");
		}
		if (vm.getState().equals(VMState.STATE_POWERED_OFF)) {
			throw new CannotSuspendVMException("Unable to go to suspend state while virtual machine is shutting down");
		}
		String suspendVMCmd = String.format("PowerShell -File \"src/main/resources/suspend_VM.ps1\" %s %s %s %s",
				serverIp, serverUsername, serverPassword, name);
		try (PowerShell psSession = PowerShell.open()) {
			psSession.executeCommands(suspendVMCmd);
			vm.setState(VMState.STATE_SUSPENDED);
			VM changedVM = vmService.save(vm);
			changedVM.setIp(null);
			return ResponseEntity.ok(changedVM);
		} catch (IOException | PowerShellExecutionException ex) {
			ex.printStackTrace();
		}
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Fail to suspend VM " + name);
	}

	@GetMapping("/VM/stop_VM/{name}")
	public ResponseEntity<Object> stopVM(@PathVariable("name") @NotBlank String name) {
		VM vm = vmService.findByName(name);
		if (vm == null) {
			throw new NotFoundVMException("Not found VM with name: " + name);
		}
		if (vm.getState().equals(VMState.STATE_POWERED_OFF)) {
			throw new VMAlreadyInStateException("VM " + name + " already in power off state");
		}
		String stopVMCmd = String.format("PowerShell -File \"src/main/resources/stop_VM.ps1\" %s %s %s %s", serverIp,
				serverUsername, serverPassword, name);
		try (PowerShell psSession = PowerShell.open()) {
			psSession.executeCommands(stopVMCmd);
			vm.setState(VMState.STATE_POWERED_OFF);
			vm.setIp(null);
			return ResponseEntity.ok(vmService.save(vm));
		} catch (IOException | PowerShellExecutionException ex) {
			ex.printStackTrace();
		}
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Fail to suspend VM " + name);
	}

	@GetMapping("/VM/update_hard_disk_VM")
	public ResponseEntity<Object> updateHardDisk(
			@Param("name") @NotBlank String name,
			@Param("storage") @Min(value = 1) String storage) {
		Integer storageGB = Integer.parseInt(storage);
		VM vm = vmService.findByName(name);
		if (vm == null) {
			throw new NotFoundVMException("Not found VM with name: " + name);
		}
		if (!vm.getState().equals(VMState.STATE_POWERED_OFF)) {
			throw new CannotUpdateVMException("you can only update the VM when it's turned off");
		}
		if (vm.getStorage() >= storageGB) {
			throw new CannotUpdateStorageException(
					"You can only increase the hard drive capacity of the virtual machine");
		}
		String updateHardDiskVMCmd = String.format("PowerShell -File \"src/main/resources/update_hard_disk_VM.ps1\" %s %s %s %s %d",
				serverIp, serverUsername, serverPassword, name, storageGB);
		try (PowerShell psSession = PowerShell.open()) {
			psSession.executeCommands(updateHardDiskVMCmd);
			vm.setStorage(storageGB);
			return ResponseEntity.ok(vmService.save(vm));
		} catch (IOException | PowerShellExecutionException ex) {
			ex.printStackTrace();
		}
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Fail to update hard disk VM " + name);
	}
	
	@PostMapping("/VM/update_cpu_ram_VM")
	public ResponseEntity<Object> updateRamCpu(@Valid @RequestBody UpdateCpuRam updateCpuRam) {
		VM vm = vmService.findByName(updateCpuRam.getName());
		if (vm == null) {
			throw new NotFoundVMException("Not found VM with name: " + updateCpuRam.getName());
		}
		if (vm.getNumCPU() == updateCpuRam.getNumCPU() && vm.getRamGB() == updateCpuRam.getRamGB()) {
			return ResponseEntity.ok(vm);
		}
		if (vm.getOs().equals(OSName.OS_UBUNTU)) {
			if (updateCpuRam.getNumCPU() < 1 || updateCpuRam.getRamGB() < 1) {
				throw new InsufficientConfigurationParametersException("Configuration parameters are not enough");
			}
		}
		if (vm.getOs().equals(OSName.OS_WINDOW)) {
			if (updateCpuRam.getNumCPU() < 1 || updateCpuRam.getRamGB() < 2) {
				throw new InsufficientConfigurationParametersException("Configuration parameters are not enough");
			}
		}
		if (!vm.getState().equals(VMState.STATE_POWERED_OFF)) {
			throw new CannotUpdateVMException("you can only update the VM when it's turned off");
		}
		String updateCpuRamVMCmd = String.format("PowerShell -File \"src/main/resources/update_CPU_RAM_VM.ps1\" %s %s %s %s %d %d",
				serverIp, serverUsername, serverPassword, updateCpuRam.getName(), updateCpuRam.getNumCPU(), updateCpuRam.getRamGB());
		try (PowerShell psSession = PowerShell.open()) {
			psSession.executeCommands(updateCpuRamVMCmd);
			vm.setNumCPU(updateCpuRam.getNumCPU());
			vm.setRamGB(updateCpuRam.getRamGB());
			return ResponseEntity.ok(vmService.save(vm));
		} catch (IOException | PowerShellExecutionException ex) {
			ex.printStackTrace();
		}
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Fail to update CPU or RAM VM " + updateCpuRam.getName());
	}
}
