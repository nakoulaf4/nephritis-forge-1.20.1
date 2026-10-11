#![allow(clippy::missing_safety_doc)]

const FLAG_PUSHABLE: u32 = 1 << 0;
const FLAG_VEHICLE: u32 = 1 << 1;
const FLAG_PASSENGER: u32 = 1 << 2;
const FLAG_NO_PHYSICS: u32 = 1 << 3;

const MAX_ENTITIES: usize = 2048;
const BUCKETS: usize = 8192;
const MIN_CONTACT: f64 = 0.01;
const PUSH_SCALE: f64 = 0.05;
const MIN_CELL_SIZE: f64 = 2.0;
const STRIDE_IN: usize = 8;
const STRIDE_OUT: usize = 4;

#[derive(Clone, Copy)]
struct CellEntry {
    key: i64,
    head: i32,
}

impl CellEntry {
    const EMPTY: Self = Self { key: 0, head: -1 };
}

#[inline]
fn hash_key(key: i64) -> usize {
    let mut x = key as u64;
    x ^= x >> 33;
    x = x.wrapping_mul(0xff51afd7ed558ccdu64);
    x ^= x >> 33;
    x = x.wrapping_mul(0xc4ceb9fe1a85ec53u64);
    x ^= x >> 33;
    x as usize & (BUCKETS - 1)
}

#[inline]
fn cell_key(x: i32, z: i32) -> i64 {
    ((z as i64) << 32) ^ (x as u32 as i64)
}

fn find_cell(cells: &mut [CellEntry; BUCKETS], key: i64, create: bool) -> Option<usize> {
    let mut slot = hash_key(key);
    for _ in 0..BUCKETS {
        let cell = cells[slot];
        if cell.head == -1 {
            if !create {
                return None;
            }
            cells[slot] = CellEntry { key, head: -1 };
            return Some(slot);
        }
        if cell.key == key {
            return Some(slot);
        }
        slot = (slot + 1) & (BUCKETS - 1);
    }
    None
}

#[inline]
fn cell_coord(value: f64, size: f64) -> i32 {
    (value / size).floor() as i32
}

#[no_mangle]
pub unsafe extern "C" fn nephritis_compute_cramming(
    request: *const f64,
    result: *mut f64,
    count: i32,
) {
    if request.is_null() || result.is_null() || count <= 0 || count as usize > MAX_ENTITIES {
        return;
    }
    let n = count as usize;
    let input = std::slice::from_raw_parts(request, n * STRIDE_IN);
    let output = std::slice::from_raw_parts_mut(result, n * STRIDE_OUT);

    for i in 0..n {
        output[i * STRIDE_OUT] = input[i * STRIDE_IN];
        output[i * STRIDE_OUT + 1] = 0.0;
        output[i * STRIDE_OUT + 2] = 0.0;
        output[i * STRIDE_OUT + 3] = 0.0;
    }

    let mut max_half = 0.0f64;
    for i in 0..n {
        max_half = max_half.max(input[i * STRIDE_IN + 4]);
    }
    let cell_size = (2.0 * max_half).max(MIN_CELL_SIZE);

    let mut cells = [CellEntry::EMPTY; BUCKETS];
    let mut next = [-1i32; MAX_ENTITIES];

    for i in 0..n {
        let base = i * STRIDE_IN;
        let cx = cell_coord(input[base + 2], cell_size);
        let cz = cell_coord(input[base + 3], cell_size);
        let key = cell_key(cx, cz);
        let Some(slot) = find_cell(&mut cells, key, true) else { return; };
        next[i] = cells[slot].head;
        cells[slot].head = i as i32;
    }

    for i in 0..n {
        let base_a = i * STRIDE_IN;
        let flags_a = input[base_a + 1] as u32;
        if (flags_a & FLAG_NO_PHYSICS) != 0 {
            continue;
        }
        let x_a = input[base_a + 2];
        let z_a = input[base_a + 3];
        let half_a = input[base_a + 4];
        let min_y_a = input[base_a + 5];
        let max_y_a = input[base_a + 6];
        let root_a = input[base_a + 7] as i64;
        let cx = cell_coord(x_a, cell_size);
        let cz = cell_coord(z_a, cell_size);

        for ox in -1..=1 {
            for oz in -1..=1 {
                let key = cell_key(cx + ox, cz + oz);
                let Some(slot) = find_cell(&mut cells, key, false) else { continue; };
                let mut j = cells[slot].head;
                while j >= 0 {
                    let j_usize = j as usize;
                    if j_usize > i {
                        let base_b = j_usize * STRIDE_IN;
                        let flags_b = input[base_b + 1] as u32;
                        if (flags_b & FLAG_NO_PHYSICS) == 0 {
                            let root_b = input[base_b + 7] as i64;
                            if root_a != root_b {
                                let dx = input[base_b + 2] - x_a;
                                let dz = input[base_b + 3] - z_a;
                                let half_sum = half_a + input[base_b + 4];
                                if dx.abs() < half_sum
                                    && dz.abs() < half_sum
                                    && min_y_a < input[base_b + 6]
                                    && input[base_b + 5] < max_y_a
                                {
                                    let push_b = (flags_b & FLAG_PUSHABLE) != 0;
                                    let passenger_b = (flags_b & FLAG_PASSENGER) != 0;
                                    let push_a = (flags_a & FLAG_PUSHABLE) != 0;
                                    let passenger_a = (flags_a & FLAG_PASSENGER) != 0;
                                    if push_b && !passenger_b {
                                        output[i * STRIDE_OUT + 3] += 1.0;
                                    }
                                    if push_a && !passenger_a {
                                        output[j_usize * STRIDE_OUT + 3] += 1.0;
                                    }

                                    let f = dx.abs().max(dz.abs());
                                    if f >= MIN_CONTACT {
                                        let sqrt_f = f.sqrt();
                                        let dn = dx / sqrt_f;
                                        let en = dz / sqrt_f;
                                        let g = (1.0 / sqrt_f).min(1.0);
                                        let pdx = dn * g * PUSH_SCALE;
                                        let pdz = en * g * PUSH_SCALE;
                                        if push_a && (flags_a & FLAG_VEHICLE) == 0 {
                                            output[i * STRIDE_OUT + 1] -= pdx;
                                            output[i * STRIDE_OUT + 2] -= pdz;
                                        }
                                        if push_b && (flags_b & FLAG_VEHICLE) == 0 {
                                            output[j_usize * STRIDE_OUT + 1] += pdx;
                                            output[j_usize * STRIDE_OUT + 2] += pdz;
                                        }
                                    }
                                }
                            }
                        }
                    }
                    j = next[j_usize];
                }
            }
        }
    }
}


const RBFS_MAX_NODES: usize = 32768;
const RBFS_STRIDE: usize = 9;

/// Breadth-first redstone power propagation.
///
/// Request layout per node:
///   [external_power, target_0, ..., target_7]
/// Target indexes are -1 for empty/unusable connections.
///
/// Result layout:
///   [final_power_0, ..., final_power_n]
///
/// This intentionally knows nothing about Minecraft objects; graph construction and
/// all world reads/writes stay on the Java side of the Nalim boundary.
#[no_mangle]
pub unsafe extern "C" fn nephritis_compute_redstone_bfs(
    request: *const f64,
    result: *mut f64,
    count: i32,
) {
    if request.is_null() || result.is_null() || count <= 0 || count as usize > RBFS_MAX_NODES {
        return;
    }

    let n = count as usize;
    let input = std::slice::from_raw_parts(request, n * RBFS_STRIDE);
    let output = std::slice::from_raw_parts_mut(result, n);

    for i in 0..n {
        output[i] = input[i * RBFS_STRIDE].clamp(0.0, 15.0);
    }

    let mut queue = Vec::with_capacity(n);
    let mut head = 0usize;
    for i in 0..n {
        if output[i] > 0.0 {
            queue.push(i as i32);
        }
    }

    while head < queue.len() {
        let source = queue[head] as usize;
        head += 1;
        let next_power = output[source] as i32 - 1;
        if next_power <= 0 {
            continue;
        }

        let base = source * RBFS_STRIDE;
        for edge in 0..8 {
            let raw_target = input[base + 1 + edge];
            if !raw_target.is_finite() { continue; }
            let target = raw_target as i32;
            if target < 0 || target as usize >= n {
                continue;
            }
            let target_index = target as usize;
            if (next_power as f64) > output[target_index] {
                output[target_index] = next_power as f64;
                queue.push(target);
            }
        }
    }
}
